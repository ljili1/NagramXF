package org.tcp2ws;

import com.neovisionaries.ws.client.WebSocket;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

public class tcp2wsServer {

    protected int port;
    protected boolean stopping = false;
    protected static boolean tls = false;
    protected static String userAgent = "tcp2ws/1.0.0";
    protected static String connHash = "";

    static Map<String, String> cdn = new HashMap<>();
    static Map<Integer, String> mtpcdn = new HashMap<>();
    static final Map<String, HashSet<WebSocket>> inactiveWs = new HashMap<>();

    public tcp2wsServer setCdnDomain(String domain) {
        mtpcdn.put(1, "pluto." + domain);
        mtpcdn.put(2, "venus." + domain);
        mtpcdn.put(3, "aurora." + domain);
        mtpcdn.put(4, "vesta." + domain);
        mtpcdn.put(5, "flora." + domain);
        mtpcdn.put(17, "test_pluto." + domain);
        mtpcdn.put(18, "test_venus." + domain);
        mtpcdn.put(19, "test_aurora." + domain);

//media
        cdn.put("149.154.175.50", "pluto." + domain);
        cdn.put("149.154.167.51", "venus." + domain);
        cdn.put("95.161.76.100", "venus." + domain);
        cdn.put("149.154.175.100", "aurora." + domain);
        cdn.put("149.154.167.91", "vesta." + domain);
        cdn.put("149.154.171.5", "flora." + domain);

        try {
            cdn.put(InetAddress.getByName("2001:b28:f23d:f001:0000:0000:0000:000a").getHostAddress(), "pluto." + domain);
            cdn.put(InetAddress.getByName("2001:67c:4e8:f002:0000:0000:0000:000a").getHostAddress(), "venus." + domain);
            cdn.put(InetAddress.getByName("2001:b28:f23d:f003:0000:0000:0000:000a").getHostAddress(), "aurora." + domain);
            cdn.put(InetAddress.getByName("2001:67c:4e8:f004:0000:0000:0000:000a").getHostAddress(), "vesta." + domain);
            cdn.put(InetAddress.getByName("2001:b28:f23f:f005:0000:0000:0000:000a").getHostAddress(), "flora." + domain);
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }
//proxy
        cdn.put("149.154.175.5", "pluto." + domain);
        cdn.put("149.154.161.144", "venus." + domain);
        cdn.put("149.154.167.15", "venus." + domain);
        cdn.put("149.154.167.5", "venus." + domain);
        cdn.put("149.154.167.6", "venus." + domain);
        cdn.put("149.154.167.7", "venus." + domain);
        cdn.put("149.154.167.2", "venus." + domain);
        // DC2 also answers on .41, which is the address Telegram's own config hands
        // out for dc2. It was absent while .51 (dc2) and .40 (test dc2) were listed,
        // so every dc2 connection to .41 was refused. The lookup in Socks4Impl#getCdn
        // only ever strips up to 3 trailing characters, so ".41" cannot fall back
        // onto ".4" - the gap has to be closed by an explicit entry.
        cdn.put("149.154.167.41", "venus." + domain);
        cdn.put("91.108.4.", "vesta." + domain);
        cdn.put("149.154.164.", "vesta." + domain);
        cdn.put("149.154.165.", "vesta." + domain);
        cdn.put("149.154.166.", "vesta." + domain);
        cdn.put("149.154.167.8", "vesta." + domain);
        cdn.put("149.154.167.9", "vesta." + domain);
        cdn.put("91.108.56.", "flora." + domain);
        cdn.put("111.62.91.", "venus." + domain);
        // Whole-subnet catch-alls for the two ranges Telegram keeps a single
        // datacenter in. Keys above are looked up first (i == 0 in getCdn), so every
        // host that already has an exact entry keeps its own mapping - including the
        // exceptions living inside these /24s: .40 (test dc2), .51, .91, .8, .9 and
        // .100 (dc3). These rows only decide hosts that are otherwise unmapped and
        // therefore refused outright, which is a guaranteed failure either way.
        cdn.put("149.154.167.", "venus." + domain);
        cdn.put("149.154.175.", "pluto." + domain);

        // IPv6 datacenter blocks. The host part of a datacenter's IPv6 address is not
        // stable: the rows above pin the few hosts this app bakes in ("…::a",
        // "…::d", "…::e"), but Telegram's own config hands out other hosts inside the
        // same /64 - "2001:67c:4e8:f004::b" was captured live on this device. The
        // host-level rows cannot cover those, because Socks4Impl#getCdn only strips up
        // to 3 trailing characters: for "…f004::b" that probes "…f004::b", "…f004::",
        // "…f004:" and "…f004", and none of them is a key. The tunnel was therefore
        // refused outright and the client retried once per second.
        //
        // Keying the whole /64 closes that gap for every host Telegram may publish.
        // The host rows still win: getCdn runs the exact/3-char lookup first, so the
        // test variants ("…::e") and the dedicated proxy variants ("…::d") keep their
        // own subdomain, and only hosts inside a known block that have no row of their
        // own fall through to here.
        cdn.put("2001:b28:f23d:f001:", "pluto." + domain);
        cdn.put("2001:67c:4e8:f002:", "venus." + domain);
        cdn.put("2001:b28:f23d:f003:", "aurora." + domain);
        cdn.put("2001:67c:4e8:f004:", "vesta." + domain);
        cdn.put("2001:b28:f23f:f005:", "flora." + domain);

        try {
            cdn.put(InetAddress.getByName("2001:b28:f23d:f001:0000:0000:0000:000d").getHostAddress(), "pluto." + domain);
            cdn.put(InetAddress.getByName("2001:67c:4e8:f002:0000:0000:0000:000d").getHostAddress(), "venus." + domain);
            cdn.put(InetAddress.getByName("2001:b28:f23d:f003:0000:0000:0000:000d").getHostAddress(), "aurora." + domain);
            cdn.put(InetAddress.getByName("2001:67c:4e8:f004:0000:0000:0000:000d").getHostAddress(), "vesta." + domain);
            cdn.put(InetAddress.getByName("2001:b28:f23f:f005:0000:0000:0000:000d").getHostAddress(), "flora." + domain);
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }

//test
        cdn.put("149.154.175.10", "test_pluto." + domain);
        cdn.put("149.154.175.40", "test_pluto." + domain);
        cdn.put("149.154.167.40", "test_venus." + domain);
        cdn.put("149.154.175.117", "test_aurora." + domain);

        try {
            cdn.put(InetAddress.getByName("2001:b28:f23d:f001:0000:0000:0000:000e").getHostAddress(), "test_pluto." + domain);
            cdn.put(InetAddress.getByName("2001:67c:4e8:f002:0000:0000:0000:000e").getHostAddress(), "test_venus." + domain);
            cdn.put(InetAddress.getByName("2001:b28:f23d:f003:0000:0000:0000:000e").getHostAddress(), "test_aurora." + domain);
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }

        inactiveWs.put(tcp2wsServer.mtpcdn.get(1), new HashSet<>());
        inactiveWs.put(tcp2wsServer.mtpcdn.get(2), new HashSet<>());
        inactiveWs.put(tcp2wsServer.mtpcdn.get(3), new HashSet<>());
        inactiveWs.put(tcp2wsServer.mtpcdn.get(4), new HashSet<>());
        inactiveWs.put(tcp2wsServer.mtpcdn.get(5), new HashSet<>());
        inactiveWs.put(tcp2wsServer.mtpcdn.get(17), new HashSet<>());
        inactiveWs.put(tcp2wsServer.mtpcdn.get(18), new HashSet<>());
        inactiveWs.put(tcp2wsServer.mtpcdn.get(19), new HashSet<>());

        return this;
    }

    public tcp2wsServer setUserAgent(String userAgent) {
        tcp2wsServer.userAgent = userAgent;
        return this;
    }

    public tcp2wsServer setConnHash(String connHash) {
        tcp2wsServer.connHash = connHash;
        return this;
    }

    public static void main(String[] args) {

    }

    public tcp2wsServer setTls(boolean tls) {
        tcp2wsServer.tls = tls;
        return this;
    }

    /**
     * Binds the listen socket on the calling thread, then serves it on a
     * background thread.
     *
     * The bind MUST happen here instead of inside the background thread. A port
     * that is already taken has to surface as an exception out of start():
     * otherwise the caller records the relay as "started" while nothing is
     * listening, Telegram is pointed at a dead local port, and there is no way
     * back for the rest of the process - the cached port is returned forever and
     * the accept loop's IOException is swallowed by a thread nobody observes.
     */
    public synchronized void start(int listenPort) throws IOException {
        if (cdn.isEmpty()) {
            throw new RuntimeException("cdn domain not set");
        }
        final ServerSocket listenSocket = new ServerSocket(listenPort);
        listenSocket.setSoTimeout(SocksConstants.LISTEN_TIMEOUT);
        this.stopping = false;
        // Bound already: the effective port is known before the thread starts.
        this.port = listenSocket.getLocalPort();
        new Thread(new ServerProcess(listenSocket)).start();
    }

    public synchronized void stop() {
        stopping = true;
    }

    private class ServerProcess implements Runnable {

        private final ServerSocket listenSocket;

        ServerProcess(ServerSocket listenSocket) {
            this.listenSocket = listenSocket;
        }

        @Override
        public void run() {
            try {
                handleClients(listenSocket);
            } catch (IOException e) {
                // Not expected anymore (the bind is done in start()), but it must
                // not be swallowed silently: this is the relay the whole built-in
                // ws proxy depends on, and if the accept loop ever ends every
                // subsequent client connection is refused with no other trace.
                RelayLog.e("relay accept loop ended on port " + port, e);
            }
        }

        protected void handleClients(ServerSocket listenSocket) throws IOException {
            while (true) {
                synchronized (tcp2wsServer.this) {
                    if (stopping) {
                        break;
                    }
                }
                handleNextClient(listenSocket);
            }

            try {
                listenSocket.close();
            } catch (IOException e) {
                // ignore
            }
        }

        private void handleNextClient(ServerSocket listenSocket) {
            try {
                final Socket clientSocket = listenSocket.accept();
                // Don't override the read timeout here - the ProxyHandler constructor
                // sets it (see RELAY_READ_TIMEOUT_MS).
                new Thread(new ProxyHandler(clientSocket)).start();
            } catch (InterruptedIOException e) {
                //	This exception is thrown when accept timeout is expired
            } catch (Exception e) {
                // A transient accept failure must not be invisible: a relay that keeps
                // failing to accept looks exactly like "the ws proxy cannot connect".
                RelayLog.e("accept failed on port " + port, e);
            }
        }
    }
}
