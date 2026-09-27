package org.tcp2ws;

import com.neovisionaries.ws.client.WebSocket;
import com.neovisionaries.ws.client.WebSocketAdapter;
import com.neovisionaries.ws.client.WebSocketException;
import com.neovisionaries.ws.client.WebSocketFactory;
import com.neovisionaries.ws.client.WebSocketFrame;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.util.Arrays;
import java.util.HashSet;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

@SuppressWarnings("SynchronizeOnNonFinalField")
public class ProxyHandler implements Runnable {

    /**
     * Keep-alive tick. The watchdog only wakes up on this interval; the actual ping is
     * sent based on how long the tunnel has been silent.
     */
    private static final long KEEPALIVE_TICK_MS = 20_000L;

    /**
     * Send a keep-alive ping once the tunnel has been silent for this long. Well inside
     * Cloudflare's ~100 s idle close, and compatible with Telegram's own MTProto
     * keep-alive.
     */
    private static final long KEEPALIVE_PING_IDLE_MS = 45_000L;

    /**
     * Zero inbound frames for this long means the upstream is a half-open socket.
     *
     * A Cloudflare Worker that recycles, a NAT rebind on a mobile network or a
     * Wi-Fi/cellular handover all leave the WebSocket half-open: `isOpen()` keeps
     * returning true, no close frame arrives and no error is raised, so the tunnel
     * looks healthy while nothing can pass through it any more. Left undetected,
     * Telegram keeps writing into that hole until its own timeout fires - the
     * "connected to the proxy but nothing moves, then a reconnect" symptom. Closing
     * the client socket makes Telegram re-establish the connection at once.
     *
     * The threshold is deliberately generous. A healthy tunnel cannot be silent for
     * anywhere near this long: Telegram sends an MTProto ping roughly every 60 s and
     * its pong is an inbound frame, and a WebSocket-level pong counts as one too. A
     * short threshold (one unanswered ping) would risk closing tunnels that are merely
     * idle - i.e. it would *create* the periodic reconnects it is meant to remove.
     */
    private static final long UPSTREAM_DEAD_AFTER_SILENCE_MS = 100_000L;

    /** First retry delay when dialling the upstream; doubles up to [MAX_DIAL_BACKOFF_MS]. */
    private static final long INITIAL_DIAL_BACKOFF_MS = 250L;
    private static final long MAX_DIAL_BACKOFF_MS = 2_000L;

    private InputStream m_ClientInput = null;
    private OutputStream m_ClientOutput = null;

    private Object m_lock;

    volatile Socket m_ClientSocket;

    /**
     * Written by the relay thread, read by the keep-alive/watchdog thread - hence
     * volatile.
     */
    volatile WebSocket m_ServerSocket = null;

    byte[] m_Buffer = new byte[SocksConstants.RELAY_BUF_SIZE];
    final static byte[] emptyBytes = new byte[8];
    String server;

    Cipher outgoingDecryptCipher;

    boolean isHandshake = false;

    /**
     * Closing this handler twice is normal (the relay loop, the watchdog and the
     * WebSocket listener all clean up), and the second pass must not flush, close or
     * log again - a repeated `stopKeepAlive()` during teardown used to log an error
     * for a tunnel that had ended perfectly normally.
     */
    private volatile boolean closed = false;

    // WebSocket keep-alive: Cloudflare closes idle WebSocket after ~100s,
    // so we send a ping well inside that window to keep the connection alive.
    private volatile boolean keepAliveRunning = false;
    private Thread keepAliveThread;

    /**
     * Wall clock of the last frame received from the upstream (binary payload or
     * pong). Used to tell a healthy idle tunnel from a half-open one - see
     * [UPSTREAM_DEAD_AFTER_SILENCE_MS].
     */
    private volatile long lastInboundAtMillis = 0L;

    public ProxyHandler(Socket clientSocket) {
        m_lock = this;
        m_ClientSocket = clientSocket;
        try {
            // A *blocking* read, not the 10 ms poll this used to be: see
            // RELAY_READ_TIMEOUT_MS. close() still ends the read immediately because
            // it closes the socket from another thread.
            m_ClientSocket.setSoTimeout(SocksConstants.RELAY_READ_TIMEOUT_MS);
        } catch (SocketException e) {
            RelayLog.e("cannot set the relay read timeout", e);
        }
    }

    public void setLock(Object lock) {
        this.m_lock = lock;
    }

    public void run() {
        setLock(this);

        if (prepareClient()) {
            processRelay();
            close();
        }
    }

    public void close() {
        if (closed) {
            return;
        }
        closed = true;

        // Stop keep-alive first
        stopKeepAlive();

        try {
            if (m_ClientOutput != null) {
                m_ClientOutput.flush();
                m_ClientOutput.close();
            }
        } catch (IOException e) {
            // ignore
        }

        // Closing the socket is what unblocks the blocking read in the relay loop.
        try {
            if (m_ClientSocket != null) {
                m_ClientSocket.close();
            }
        } catch (IOException e) {
            // ignore
        }

        // Close WebSocket - don't pool since reuse is unreliable (listener binding issue)
        if (m_ServerSocket != null) {
            if (m_ServerSocket.isOpen()) {
                m_ServerSocket.sendClose();
            }
            m_ServerSocket = null;
        }

        m_ClientSocket = null;
    }

    public void sendToClient(byte[] buffer) {
        sendToClient(buffer, buffer.length);
    }

    public void sendToClient(byte[] buffer, int len) {
        if (m_ClientOutput != null && len > 0 && len <= buffer.length) {
            try {
                m_ClientOutput.write(buffer, 0, len);
                m_ClientOutput.flush();
            } catch (IOException e) {
                // The client socket is gone. Deliberately not closed from here: this runs
                // on the WebSocket listener thread, and the relay loop notices the same
                // dead socket on its own read (promptly - a reset or an EOF, not the full
                // read timeout). Logged instead of swallowed so a tunnel that dies
                // mid-stream is visible.
                RelayLog.d("client write failed: " + e.getMessage());
            }
        }
    }

    /**
     * Establishes the upstream WebSocket for [server].
     *
     * Throws when no upstream could be established, and that is the whole point:
     * the caller must be able to tell the client that the proxy could not be used.
     * It used to return normally with `m_ServerSocket == null`, so the SOCKS success
     * reply had already been written when the tunnel died one line later - the
     * client saw a connection that was established and instantly dropped, and
     * retried in a loop (the "keeps reconnecting to the proxy" symptom).
     */
    public void connectToServer(String server) throws IOException {
        if (server == null || server.isEmpty()) {
            throw new IOException("no ws upstream is configured for this destination");
        }
        this.server = server;
        prepareServer();
    }

    private void startKeepAlive() {
        if (keepAliveRunning || m_ServerSocket == null) return;
        keepAliveRunning = true;
        lastInboundAtMillis = System.currentTimeMillis();
        keepAliveThread = new Thread(() -> {
            while (keepAliveRunning && m_ServerSocket != null && m_ServerSocket.isOpen()) {
                try {
                    Thread.sleep(KEEPALIVE_TICK_MS);
                    if (!keepAliveRunning || m_ServerSocket == null || !m_ServerSocket.isOpen()) {
                        break;
                    }
                    final long silentFor = System.currentTimeMillis() - lastInboundAtMillis;
                    if (silentFor >= UPSTREAM_DEAD_AFTER_SILENCE_MS) {
                        // Nothing at all came back for far longer than any healthy
                        // tunnel is ever quiet. Close so the client reconnects now
                        // instead of waiting out its own timeout on a dead socket.
                        RelayLog.d("ws upstream silent for " + silentFor + " ms (" + server
                                + "), closing the tunnel so the client reconnects");
                        close();
                        break;
                    }
                    if (silentFor >= KEEPALIVE_PING_IDLE_MS) {
                        // Keeps Cloudflare from closing an idle tunnel. A pong (or any
                        // frame) resets lastInboundAtMillis through the listener.
                        m_ServerSocket.sendPing();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    // WebSocket might be closed, stop keep-alive
                    break;
                }
            }
            keepAliveRunning = false;
        });
        keepAliveThread.setDaemon(true);
        keepAliveThread.setName("ws-keepalive-" + server);
        keepAliveThread.start();
    }

    private void stopKeepAlive() {
        keepAliveRunning = false;
        if (keepAliveThread != null) {
            keepAliveThread.interrupt();
            keepAliveThread = null;
        }
    }

    protected void prepareServer() throws IOException {
        synchronized (m_lock) {
            // Don't reuse pooled WebSockets: they retain old ProxyHandler listeners,
            // causing data to be sent to closed SOCKS sockets and triggering reconnect
            // loops. Instead, close stale pooled connections and always create fresh
            // ones.
            HashSet<WebSocket> set = tcp2wsServer.inactiveWs.get(server);
            if (set != null) {
                for (WebSocket ws : set) {
                    if (ws.isOpen()) {
                        ws.sendClose();
                    }
                }
                set.clear();
            }

            // Bounded retry instead of a single attempt. A Cloudflare Worker answer of
            // 520 / 1102 is the *documented* transient case, but a connection reset, a
            // TLS hiccup or a slow edge node is just as transient - and the old code
            // retried only the failures whose message happened to contain "520" while
            // giving up on everything else immediately. Each failed dial was a client
            // connection that could not be established, i.e. a reconnect.
            final long deadline = System.currentTimeMillis() + SocksConstants.UPSTREAM_DIAL_BUDGET_MS;
            long backoff = INITIAL_DIAL_BACKOFF_MS;
            Throwable lastError = null;

            while (true) {
                try {
                    m_ServerSocket = dialUpstream();
                    RelayLog.d("ws upstream connected: " + server);
                    return;
                } catch (IOException | WebSocketException e) {
                    // Both failure kinds are retried: `createSocket` reports a malformed
                    // target as an IOException, `connect` reports every transport-level
                    // failure as a WebSocketException.
                    lastError = e;
                    m_ServerSocket = null;
                    final String reason = describe(e);
                    RelayLog.d("ws upstream dial failed (" + server + "): " + reason);
                    if (System.currentTimeMillis() + backoff >= deadline) {
                        break;
                    }
                    try {
                        Thread.sleep(backoff);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    backoff = Math.min(backoff * 2, MAX_DIAL_BACKOFF_MS);
                }
            }

            // No upstream: the caller MUST be told, so it can refuse the SOCKS request
            // instead of reporting a success the tunnel cannot honour.
            throw new IOException("cannot reach ws upstream " + server, lastError);
        }
    }

    /** Opens one WebSocket to [server]; the caller owns the result. */
    private WebSocket dialUpstream() throws IOException, WebSocketException {
        return new WebSocketFactory()
                // Kept short on purpose: the retry loop above owns the total budget,
                // so one stalled edge node cannot consume the whole of it. 3 s fits
                // two attempts inside UPSTREAM_DIAL_BUDGET_MS, which itself stays
                // under the client's own 8 s handshake deadline.
                .setConnectionTimeout(3000)
                .createSocket((tcp2wsServer.tls ? "wss://" : "ws://") + server + "/api")
                .addListener(new WebSocketAdapter() {
                    public void onBinaryMessage(WebSocket websocket, byte[] binary) {
                        lastInboundAtMillis = System.currentTimeMillis();
                        sendToClient(binary);
                    }

                    public void onPongFrame(WebSocket websocket, WebSocketFrame frame) {
                        lastInboundAtMillis = System.currentTimeMillis();
                    }

                    public void onDisconnected(WebSocket websocket, WebSocketFrame serverCloseFrame, WebSocketFrame clientCloseFrame, boolean closedByServer) {
                        // Handle ALL disconnect cases, not just server-initiated ones
                        RelayLog.d("ws disconnected: server=" + server + ", closedByServer=" + closedByServer
                                + (serverCloseFrame != null ? ", closeCode=" + serverCloseFrame.getCloseCode() : ""));
                        stopKeepAlive();
                        if (m_ServerSocket != null && m_ServerSocket.isOpen()) {
                            m_ServerSocket.sendClose();
                        }
                        close();
                    }

                    public void onError(WebSocket websocket, WebSocketException cause) {
                        RelayLog.e("ws error: server=" + server, cause);
                        stopKeepAlive();
                        if (m_ServerSocket != null && m_ServerSocket.isOpen()) {
                            m_ServerSocket.sendClose();
                        }
                        close();
                    }
                })
                .addExtension("permessage-deflate")
                .addProtocol("binary")
                .addHeader("User-Agent", tcp2wsServer.userAgent)
                .addHeader("Conn-Hash", tcp2wsServer.connHash)
                .connect();
    }

    /**
     * Null-safe, informative description of a dial failure.
     *
     * The old code called `e.getMessage().contains("520")` directly: a
     * `WebSocketException` without a message (a plain connect failure) threw an NPE
     * from inside the catch block, which escaped before the SOCKS reply was written
     * at all - the client then waited for a handshake answer that never came.
     */
    private static String describe(Throwable e) {
        String message = e.getMessage();
        if (message == null || message.isEmpty()) {
            message = e.getClass().getSimpleName();
        }
        Throwable cause = e.getCause();
        if (cause != null && cause != e) {
            String causeMessage = cause.getMessage();
            message = message + " (" + (causeMessage == null ? cause.getClass().getSimpleName() : causeMessage) + ")";
        }
        return message;
    }

    public boolean prepareClient() {
        if (m_ClientSocket == null) return false;

        try {
            m_ClientInput = m_ClientSocket.getInputStream();
            m_ClientOutput = m_ClientSocket.getOutputStream();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public void processRelay() {
        try {
            byte SOCKS_Version = getByteFromClient();

            Socks4Impl comm;
            switch (SOCKS_Version) {
                case SocksConstants.SOCKS4_Version:
                    comm = new Socks4Impl(this);
                    break;
                case SocksConstants.SOCKS5_Version:
                    comm = new Socks5Impl(this);
                    break;
                default:
                    return;
            }

            comm.authenticate(SOCKS_Version);
            comm.getClientCommand();
            switch (comm.socksCommand) {
                case SocksConstants.SC_CONNECT:
                    comm.connect();
                    processHandshake();
                    startKeepAlive();  // Start WebSocket ping keep-alive after handshake
                    relay();
                    break;

                case SocksConstants.SC_BIND:
                    //comm.bind();
                    relay();
                    break;

                case SocksConstants.SC_UDP:
                    comm.udp();
                    break;
            }
        } catch (Exception e) {
            // Never silent: this is where a refused/failed SOCKS request surfaces, and
            // it is the only trace of why a client connection could not be tunneled.
            RelayLog.e("socks request failed (server=" + server + ")", e);
        }
    }

    public byte getByteFromClient() throws Exception {
        while (m_ClientSocket != null) {
            int b;
            try {
                b = m_ClientInput.read();
            } catch (InterruptedIOException e) {
                Thread.yield();
                continue;
            }
            return (byte) b; // return loaded byte
        }
        throw new Exception("Interrupted Reading GetByteFromClient()");
    }

    public void relay() {
        if (m_ServerSocket == null) return;

        boolean isActive = true;

        while (isActive) {

            //---> Check for client data <---

            // Blocking read (see RELAY_READ_TIMEOUT_MS): an idle tunnel costs no CPU,
            // and the timeout is what makes this loop notice a dropped client.
            int dlen = checkClientData();

            if (dlen < 0) {
                isActive = false;
            }
            if (dlen > 0) {
                try {
                    m_ServerSocket.sendBinary(Arrays.copyOf(m_Buffer, dlen));
                } catch (Exception e) {
                    // WebSocket send failed - connection is broken
                    RelayLog.d("ws send failed: server=" + server);
                    close();
                    isActive = false;
                }
            }
        }
    }

    private void processHandshake() {
        byte[] buffer = new byte[]{};
        for (int dlen = 0, _dlen; dlen < 105; dlen += _dlen) {
            _dlen = checkClientData();
            if (_dlen < 0) return;
            buffer = Utils.concat(buffer, Arrays.copyOf(m_Buffer, _dlen));
        }

        byte[] decrypted = new byte[]{};

        try {
            outgoingDecryptCipher = Cipher.getInstance("AES/CTR/NoPadding");
            outgoingDecryptCipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(Arrays.copyOfRange(buffer, 8, 40), "AES"), new IvParameterSpec(Arrays.copyOfRange(buffer, 40, 56)));
            decrypted = outgoingDecryptCipher.update(buffer);
        } catch (Exception e) {
            RelayLog.e("cannot decode the client handshake", e);
        }
        isHandshake = Arrays.equals(Arrays.copyOfRange(decrypted, 65, 73), emptyBytes);
        if (m_ServerSocket == null) {
            // The upstream was torn down between the SOCKS reply and the handshake
            // (the WebSocket listener closes the handler on a disconnect). Bailing out
            // here instead of dereferencing null means the client sees a clean close
            // rather than the handler dying with an NPE.
            return;
        }
        m_ServerSocket.sendBinary(buffer);
    }

    public int checkClientData() {
        synchronized (m_lock) {
            //	The client side is not opened.
            if (m_ClientInput == null) return -1;

            int dlen;

            try {
                // One full relay buffer per read. It used to be DEFAULT_BUF_SIZE (40
                // bytes), so every 40 bytes of traffic became its own WebSocket frame.
                dlen = m_ClientInput.read(m_Buffer, 0, SocksConstants.RELAY_BUF_SIZE);
            } catch (InterruptedIOException e) {
                // Read timeout: nothing to forward right now. Returning 0 keeps the
                // relay loop alive without spinning (the read itself costs nothing).
                return 0;
            } catch (IOException e) {
                final String message = e.getMessage();
                // Null-safe: an IOException without a message used to make this check
                // itself throw an NPE, which skipped close() and leaked the handler.
                final boolean expectedClose = message != null
                        && (message.contains("Socket Closed") || message.contains("socket closed")
                        || message.contains("Connection reset"));
                if (!expectedClose) {
                    RelayLog.e("client read failed", e);
                }
                close();    //	Close the server on this exception
                return -1;
            }

            if (dlen < 0) close();

            return dlen;
        }
    }
}
