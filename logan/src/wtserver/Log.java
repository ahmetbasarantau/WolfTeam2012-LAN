package wtserver;

/**
 * Dead-simple category log filter. Silences the firehose (UDP heartbeats, LTNET query spam)
 * while keeping the signal (room create, AI, wire messages, errors).
 *
 * Toggle at launch without recompiling:  java -Dwt.log.udp=true -Dwt.log.ltnet=true ...
 * or edit the defaults below. Categories:
 *   udp   - raw UDP dumps, 0xCC echo, type-2 register, session-key compare   (default OFF - noisy)
 *   ltnet - LithTech unconnected QUERY / QUERY_RESPONSE heartbeat            (default OFF - noisy)
 *   match - connected-UDP CONNECT/ACCEPT/PING (already rate-limited)          (default ON)
 *   wire  - per-message RX/TX msgId lines                                     (default ON)
 *   info  - general server info: room create, AI, char load, etc.            (default ON, always useful)
 * Errors are never filtered - use Log.err().
 */
public final class Log {
    public static boolean UDP   = flag("udp",   true);   // TEMP ON: debugging the peer-endpoint/type-2 handshake
    public static boolean LTNET = flag("ltnet", false);
    public static boolean MATCH = flag("match", true);
    public static boolean WIRE  = flag("wire",  true);
    public static boolean INFO  = flag("info",  true);

    private static boolean flag(String k, boolean def) {
        String v = System.getProperty("wt.log." + k);
        if (v == null) return def;
        return v.equalsIgnoreCase("true") || v.equals("1") || v.equalsIgnoreCase("on");
    }

    public static void udp(String s)   { if (UDP)   System.out.println(s); }
    public static void ltnet(String s) { if (LTNET) System.out.println(s); }
    public static void match(String s) { if (MATCH) System.out.println(s); }
    public static void wire(String s)  { if (WIRE)  System.out.println(s); }
    public static void info(String s)  { if (INFO)  System.out.println(s); }
    public static void err(String s)   { System.out.println(s); }   // errors always print

    private Log() {}
}
