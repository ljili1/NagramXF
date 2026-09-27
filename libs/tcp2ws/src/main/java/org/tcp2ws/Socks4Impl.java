package org.tcp2ws;

import static org.tcp2ws.Utils.getSocketInfo;

import com.neovisionaries.ws.client.WebSocketException;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.InetAddress;

public class Socks4Impl {

    final ProxyHandler m_Parent;
    final byte[] DST_Port = new byte[2];
    byte[] DST_Addr = new byte[4];
    byte SOCKS_Version = 0;
    byte socksCommand;


    //	private InetAddress m_ExtLocalIP = null;
    InetAddress m_ServerIP = null;
    int m_nServerPort = 0;
    InetAddress m_ClientIP = null;
    int m_nClientPort = 0;

    Socks4Impl(ProxyHandler Parent) {
        m_Parent = Parent;
    }

    public byte getSuccessCode() {
        return 90;
    }

    public byte getFailCode() {
        return 91;
    }

    @NotNull
    public String commName(byte code) {
        switch (code) {
            case 0x01:
                return "CONNECT";
/*			case 0x02:
				return "BIND";*/
            case 0x03:
                return "UDP Association";
            default:
                return "Unknown Command";
        }
    }

    @NotNull
    public String replyName(byte code) {
        switch (code) {
            case 0:
                return "SUCCESS";
            case 1:
                return "General SOCKS Server failure";
            case 2:
                return "Connection not allowed by ruleset";
            case 3:
                return "Network Unreachable";
            case 4:
                return "HOST Unreachable";
            case 5:
                return "Connection Refused";
            case 6:
                return "TTL Expired";
            case 7:
                return "Command not supported";
            case 8:
                return "Address Type not Supported";
            case 9:
                return "to 0xFF UnAssigned";
            case 90:
                return "Request GRANTED";
            case 91:
                return "Request REJECTED or FAILED";
            case 92:
                return "Request REJECTED - SOCKS server can't connect to Identd on the client";
            case 93:
                return "Request REJECTED - Client and Identd report diff user-ID";
            default:
                return "Unknown Command";
        }
    }

    public boolean isInvalidAddress(byte Atype) {
        m_ServerIP = Utils.calcInetAddress(Atype, DST_Addr);
        m_nServerPort = Utils.calcPort(DST_Port[0], DST_Port[1]);

        m_ClientIP = m_Parent.m_ClientSocket.getInetAddress();
        m_nClientPort = m_Parent.m_ClientSocket.getPort();

        return m_ServerIP == null || m_nServerPort < 0;
    }

    protected byte getByte() {
        try {
            return m_Parent.getByteFromClient();
        } catch (Exception e) {
            return 0;
        }
    }

    public void authenticate(byte SOCKS_Ver) throws Exception {
        SOCKS_Version = SOCKS_Ver;
    }

    public void getClientCommand() throws Exception {
        // Version was get in method Authenticate()
        socksCommand = getByte();

        DST_Port[0] = getByte();
        DST_Port[1] = getByte();

        for (int i = 0; i < 4; i++) {
            DST_Addr[i] = getByte();
        }

        //noinspection StatementWithEmptyBody
        while (getByte() != 0x00) {
            // keep reading bytes
        }

        if ((socksCommand < SocksConstants.SC_CONNECT) || (socksCommand > SocksConstants.SC_BIND)) {
            refuseCommand((byte) 91);
            throw new Exception("Socks 4 - Unsupported Command : " + commName(socksCommand));
        }

        if (isInvalidAddress((byte) 0x01)) {  // Gets the IP Address
            refuseCommand((byte) 92);    // Host Not Exists...
            throw new Exception("Socks 4 - Unknown Host/IP address '" + m_ServerIP.toString());
        }
    }

    public void replyCommand(byte ReplyCode) {

        byte[] REPLY = new byte[8];
        REPLY[0] = 0;
        REPLY[1] = ReplyCode;
        REPLY[2] = DST_Port[0];
        REPLY[3] = DST_Port[1];
        REPLY[4] = DST_Addr[0];
        REPLY[5] = DST_Addr[1];
        REPLY[6] = DST_Addr[2];
        REPLY[7] = DST_Addr[3];

        m_Parent.sendToClient(REPLY);
    }

    protected void refuseCommand(byte errorCode) {
        replyCommand(errorCode);
    }

    /**
     * ws upstream host that serves [m_ServerIP], or null when none is known.
     *
     * The table maps a Telegram datacenter address to the CDN subdomain that fronts
     * it. The lookup tolerates the short prefixes the table also stores
     * (`91.108.56.`), which is why trailing characters are stripped one at a time.
     *
     * IPv6 then gets a second pass keyed on the /64 block, because a datacenter's
     * IPv6 host part is not stable: this app bakes in `2001:67c:4e8:f004::a`, while
     * Telegram's own config handed out `2001:67c:4e8:f004::b` for the very same
     * datacenter. Both are in the block, and the block is what identifies the
     * datacenter - the address family is irrelevant here, because the upstream is
     * reached by name (`wss://vesta.<domain>/api`) and the Worker picks the
     * datacenter itself. The stripping pass above cannot be relied on for that: it
     * only removes up to 3 characters, so it reaches the block key for the compressed
     * `…::b` form but not for a fully expanded address.
     *
     * Unknown addresses are **refused** instead of being dialled as a bare IP. The
     * previous fallback built `wss://149.154.167.51/api`, which can never work: the
     * Worker routes by the Host/SNI name and no certificate matches a raw IP, so the
     * dial failed - but only *after* the SOCKS success reply had been written, so the
     * client saw a connection that was established and immediately dropped and
     * retried in a loop. Refusing turns an unexplainable reconnect storm into an
     * explicit, logged failure.
     */
    @Nullable
    private String getCdn() {
        final String address = m_ServerIP.getHostAddress();
        for (int i = 0; i <= 3 && i < address.length(); i++) {
            final String server = (tcp2wsServer.cdn).get(address.substring(0, address.length() - i));
            if (server != null) {
                return server;
            }
        }
        final int blockEnd = indexOfNthColon(address, 4);
        if (blockEnd > 0) {
            final String server = (tcp2wsServer.cdn).get(address.substring(0, blockEnd + 1));
            if (server != null) {
                return server;
            }
        }
        RelayLog.d("no ws upstream mapped for " + address + " - refusing the tunnel");
        return null;
    }

    /** Index of the [n]-th colon (1-based), or -1 when the string has fewer. */
    private static int indexOfNthColon(String value, int n) {
        int index = -1;
        for (int i = 0; i < n; i++) {
            index = value.indexOf(':', index + 1);
            if (index < 0) {
                return -1;
            }
        }
        return index;
    }

    public void connect() throws Exception {
        //	Connect to the Remote Host
        final String upstream = getCdn();
        if (upstream == null) {
            refuseCommand(getFailCode());
            // Returning normally would let processRelay() continue into
            // processHandshake() with no upstream at all.
            throw new IOException("no ws upstream for " + m_ServerIP.getHostAddress());
        }
        try {
            m_Parent.connectToServer(upstream);
        } catch (IOException e) {
            refuseCommand(getFailCode()); // Connection Refused
            // The old message dereferenced m_Parent.m_ServerSocket, which is exactly
            // null on this path - the reporting of the failure threw an NPE of its
            // own. Report the *upstream* that could not be reached instead.
            throw new IOException("cannot reach ws upstream " + upstream, e);
        }
        replyCommand(getSuccessCode());
    }

    public void udp() throws IOException, WebSocketException {
        refuseCommand((byte) 91);    // SOCKS4 don't support UDP
    }
}
