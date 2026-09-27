package org.tcp2ws;

public interface SocksConstants {

    // refactor
    int LISTEN_TIMEOUT = 200;
    int DEFAULT_SERVER_TIMEOUT = 1000;

    int DEFAULT_BUF_SIZE = 40;
    int DEFAULT_PROXY_TIMEOUT = 10;

    /**
     * Blocking read timeout of a relayed client socket, in milliseconds.
     *
     * This used to be [DEFAULT_PROXY_TIMEOUT] (10 ms), which turned `relay()` into
     * a busy poll: every tunnel thread woke up 100 times a second, hit the timeout
     * and spun with `Thread.yield()`. Telegram keeps several long-lived MTProto
     * connections open at once, so a handful of tunnels burned a core permanently -
     * and a process the system throttles (background, battery saver, Doze) then
     * delays the WebSocket reads and writes that carry the proxy traffic, which
     * shows up as stalled tunnels and reconnect loops.
     *
     * A blocking read costs nothing while the tunnel is idle, and `close()` still
     * ends it immediately: closing the socket from another thread makes the blocked
     * read fail instead of waiting for the timeout.
     */
    int RELAY_READ_TIMEOUT_MS = 30_000;

    /**
     * Payload size of one relayed read / WebSocket frame.
     *
     * [DEFAULT_BUF_SIZE] (40 bytes) was inherited from the SOCKS header parser and
     * was also used as the relay buffer, so every 40 bytes of client traffic became
     * its own WebSocket frame - with `permessage-deflate` negotiated, i.e. a
     * compression context operation per frame. 16 KiB matches a handful of TCP
     * segments and cuts the frame rate by roughly two orders of magnitude.
     */
    int RELAY_BUF_SIZE = 16 * 1024;

    /**
     * Upper bound on dialling the WebSocket upstream, retries included.
     *
     * Telegram gives a SOCKS5 handshake a limited time before it gives up on the
     * connection, and that deadline is *shorter* than this budget used to be: for a
     * generic connection in "trying the next address/port" state it calls
     * `setTimeout(8)` (Connection.cpp), i.e. it closes the socket 8 s after the last
     * event if nothing came back. Holding the SOCKS request for 12 s therefore meant
     * the client timed out first - it counted the attempt as a disconnect reason 2,
     * added the full timeout to `disconnectTimeoutAmount`, and reconnected while the
     * relay was still dialling. Answering *before* the client's deadline turns that
     * dead time into an explicit refusal, which the client retries in 1 s.
     *
     * 6 s leaves ~2 s of headroom under the tightest deadline (8 s) for the socket
     * setup and the SOCKS reply itself.
     */
    long UPSTREAM_DIAL_BUDGET_MS = 6_000L;

    byte SOCKS5_Version = 0x05;
    byte SOCKS4_Version = 0x04;

    byte SC_CONNECT = 0x01;
    byte SC_BIND = 0x02;
    byte SC_UDP = 0x03;
}
