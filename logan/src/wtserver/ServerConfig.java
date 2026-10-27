package wtserver;

/**
 * Central server config. ADVERTISED_IP is the address the server hands out to CLIENTS so they know where to
 * reach the server (channel list 0x1101, match-UDP destination 0x1201, char-select). It MUST be an address
 * the client can actually reach:
 *   - same-box / local testing        -> 127.0.0.1
 *   - remote clients over Hamachi/LAN  -> the server's Hamachi/LAN IP (so peers don't all resolve to loopback)
 *
 * Encoding = little-endian int, matching ServerMsg.addInteger() and the raw byte payloads (wire order = the
 * IP octets a.b.c.d):
 *   0x0100007F -> wire 7F 00 00 01 = 127.0.0.1
 *   0x98CF2919 -> wire 19 29 CF 98 = 25.41.207.152   (Hamachi)
 *
 * FLIP THIS ONE VALUE to switch. Recompile + restart.
 */
public class ServerConfig {
    public static boolean CLIENT_2012 = true;

    public static final int ADVERTISED_IP = configuredIp();
    // PUBLIC/reachable server IP handed to REMOTE clients when the socket's local interface resolves to
    // loopback (happens when a remote player reaches us through a netsh portproxy -> server sees 127.0.0.1).
    // 0x98CF2919 = 25.41.207.152 (Hamachi). Set this to the server's reachable address for remote play.
    public static final int PUBLIC_IP = ADVERTISED_IP;

    public static java.net.InetAddress bindAddress() {
        try { return java.net.InetAddress.getByName(System.getenv().getOrDefault("WOLF_BIND_IP", "127.0.0.1")); }
        catch (java.net.UnknownHostException e) { throw new IllegalArgumentException("Invalid LAN bind address", e); }
    }

    private static int configuredIp() {
        byte[] a = bindAddress().getAddress();
        if (a.length != 4) throw new IllegalArgumentException("IPv4 is required");
        return (a[0]&255) | ((a[1]&255)<<8) | ((a[2]&255)<<16) | ((a[3]&255)<<24);
    }

    /** Wire bytes (LE of ADVERTISED_IP) for byte-array payloads: {octet0, octet1, octet2, octet3}. */
    public static String publicIpStr() {
        return (PUBLIC_IP & 0xff) + "." + ((PUBLIC_IP>>8)&0xff) + "." + ((PUBLIC_IP>>16)&0xff) + "." + ((PUBLIC_IP>>24)&0xff);
    }

    public static byte[] advertisedIpBytes() {
        return new byte[] {
            (byte) (ADVERTISED_IP & 0xff),
            (byte) ((ADVERTISED_IP >> 8) & 0xff),
            (byte) ((ADVERTISED_IP >> 16) & 0xff),
            (byte) ((ADVERTISED_IP >> 24) & 0xff)
        };
    }
}
