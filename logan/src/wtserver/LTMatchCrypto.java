package wtserver;

/**
 * LithTech match-UDP crypto: standard Blowfish loaded from a raw dumped schedule
 * (the WolfTeam key is unknown, so we load the P-array + 4 S-boxes directly from
 * Ghidra's memory dump at 0x6AE468 -> bf_schedule.bin, 0x1048 = 4168 bytes).
 *
 * Packet layout (confirmed via Ghidra build-side capture):
 *   [0:8]  = Blowfish-encrypted ltjs header
 *   [8:16] = PLAINTEXT payload (not encrypted)
 * Header plaintext:
 *   [0]   fingerprint = XOR(bytes[1..7]) ^ 0x4C
 *   [1]   flags/?
 *   [2:4] session id (u16 LE)
 *   [4:6] sequence   (u16 LE)
 *   [6]   type byte  (CONNECT = 0x08)
 *   [7]   tail byte (part of fingerprint)
 * Known pair (validation): Blowfish_dec(fd ce b8 6c 89 fc d4 e1) == EE 01 01 00 00 00 08 AA
 */
public final class LTMatchCrypto {

    public static final int FINGERPRINT_XOR = 0x4C;

    private final int[]   P = new int[18];
    private final int[][] S = new int[4][256];
    private final boolean beBlock;   // how the 8-byte block splits into (L,R): big- vs little-endian halves

    /**
     * @param sched   4168-byte schedule dump (18 P + 4*256 S)
     * @param leWords true if each u32 in the dump is stored little-endian (raw x86 memory dump = true)
     * @param beBlock true if the 8-byte packet block splits into big-endian L/R halves (standard Blowfish)
     */
    public LTMatchCrypto(byte[] sched, boolean leWords, boolean beBlock) {
        if (sched.length < 0x1048)
            throw new IllegalArgumentException("schedule must be >= 4168 bytes, got " + sched.length);
        int off = 0;
        for (int i = 0; i < 18; i++)      { P[i]    = rd(sched, off, leWords); off += 4; }
        for (int b = 0; b < 4; b++)
            for (int i = 0; i < 256; i++) { S[b][i] = rd(sched, off, leWords); off += 4; }
        this.beBlock = beBlock;
    }

    private static int rd(byte[] a, int o, boolean le) {
        return le ? ( (a[o]&0xff)        | ((a[o+1]&0xff)<<8) | ((a[o+2]&0xff)<<16) | ((a[o+3]&0xff)<<24) )
                  : (((a[o]&0xff)<<24)    | ((a[o+1]&0xff)<<16)| ((a[o+2]&0xff)<<8)  |  (a[o+3]&0xff)      );
    }

    private int f(int x) {
        int a=(x>>>24)&0xff, b=(x>>>16)&0xff, c=(x>>>8)&0xff, d=x&0xff;
        return (((S[0][a] + S[1][b]) ^ S[2][c]) + S[3][d]);
    }

    /** Blowfish encrypt one 64-bit block held as two ints; returns {L,R}. */
    public int[] encLR(int L, int R) {
        for (int i = 0; i < 16; i++) { L ^= P[i]; R ^= f(L); int t = L; L = R; R = t; }
        int t = L; L = R; R = t;
        R ^= P[16]; L ^= P[17];
        return new int[]{ L, R };
    }
    /** Blowfish decrypt one 64-bit block (P-array applied in reverse). */
    public int[] decLR(int L, int R) {
        for (int i = 17; i > 1; i--) { L ^= P[i]; R ^= f(L); int t = L; L = R; R = t; }
        int t = L; L = R; R = t;
        R ^= P[1]; L ^= P[0];
        return new int[]{ L, R };
    }

    private int loadHalf(byte[] b, int o) {
        return beBlock ? (((b[o]&0xff)<<24)|((b[o+1]&0xff)<<16)|((b[o+2]&0xff)<<8)|(b[o+3]&0xff))
                       : ( (b[o]&0xff)      |((b[o+1]&0xff)<<8) |((b[o+2]&0xff)<<16)|((b[o+3]&0xff)<<24));
    }
    private void storeHalf(byte[] b, int o, int v) {
        if (beBlock) { b[o]=(byte)(v>>>24); b[o+1]=(byte)(v>>>16); b[o+2]=(byte)(v>>>8); b[o+3]=(byte)v; }
        else         { b[o]=(byte)v; b[o+1]=(byte)(v>>>8); b[o+2]=(byte)(v>>>16); b[o+3]=(byte)(v>>>24); }
    }

    /** Decrypt an 8-byte block. */
    public byte[] decryptBlock(byte[] in8) {
        int[] lr = decLR(loadHalf(in8, 0), loadHalf(in8, 4));
        byte[] out = new byte[8]; storeHalf(out, 0, lr[0]); storeHalf(out, 4, lr[1]); return out;
    }
    /** Encrypt an 8-byte block. */
    public byte[] encryptBlock(byte[] in8) {
        int[] lr = encLR(loadHalf(in8, 0), loadHalf(in8, 4));
        byte[] out = new byte[8]; storeHalf(out, 0, lr[0]); storeHalf(out, 4, lr[1]); return out;
    }

    /** ltjs header fingerprint: byte[0] = XOR(byte[1..7]) ^ 0x4C. */
    public static byte fingerprint(byte[] hdr8) {
        int x = 0; for (int i = 1; i < 8; i++) x ^= (hdr8[i] & 0xff);
        return (byte) (x ^ FINGERPRINT_XOR);
    }
    public static boolean fingerprintOk(byte[] hdr8) {
        return (hdr8[0] & 0xff) == (fingerprint(hdr8) & 0xff);
    }

    // ---- packet-level helpers (packet = [Blowfish(header 8B)][plaintext payload 8B]) ----

    public static final int TYPE_CONNECT = 0x08;   // confirmed via Ghidra build-side capture

    /** Decrypt a 16-byte match packet's header; returns the 8-byte plaintext header. */
    public byte[] decryptHeader(byte[] pkt16) {
        byte[] h = new byte[8];
        System.arraycopy(pkt16, 0, h, 0, 8);
        return decryptBlock(h);
    }
    public static int session(byte[] hdr8) { return (hdr8[2] & 0xff) | ((hdr8[3] & 0xff) << 8); }
    public static int seq    (byte[] hdr8) { return (hdr8[4] & 0xff) | ((hdr8[5] & 0xff) << 8); }
    public static int type   (byte[] hdr8) { return hdr8[6] & 0xff; }

    /**
     * Build a full 16-byte match packet: header (fingerprint auto-computed) Blowfish-encrypted,
     * then the plaintext payload appended.
     *   header = [fp][flags][session u16 LE][seq u16 LE][type][tail]
     */
    public byte[] buildPacket(int flags, int sessionId, int seq, int type, int tail, byte[] payload8) {
        byte[] hdr = new byte[8];
        hdr[1] = (byte) flags;
        hdr[2] = (byte) sessionId; hdr[3] = (byte) (sessionId >>> 8);
        hdr[4] = (byte) seq;       hdr[5] = (byte) (seq >>> 8);
        hdr[6] = (byte) type;
        hdr[7] = (byte) tail;
        hdr[0] = fingerprint(hdr);
        byte[] enc = encryptBlock(hdr);
        byte[] out = new byte[16];
        System.arraycopy(enc, 0, out, 0, 8);
        if (payload8 != null) System.arraycopy(payload8, 0, out, 8, Math.min(8, payload8.length));
        return out;
    }

    public static final int TYPE_ACCEPT = 0x01;   // header[4]&0x3F == 1 (Ghidra validate @0x1A87AEE8)

    /** True if this decrypted header is a CONNECT (type field header[4]&0x3F == 0). */
    public static boolean isConnect(byte[] hdr8) { return (hdr8[4] & 0x3F) == 0x00; }

    /**
     * Build the CONNECT-ACCEPT for a received 16-byte CONNECT packet:
     *   mirror the CONNECT header, set the type field (header[4] low 6 bits) 0 -> 1,
     *   recompute the ^0x4C fingerprint, Blowfish-encrypt [0:8], echo the plaintext payload [8:16].
     * Per Ghidra's validate the accept only needs the header right; payload isn't checked.
     */
    public byte[] buildAcceptFromConnect(byte[] connectPkt16) {
        byte[] hdr = decryptHeader(connectPkt16);          // plaintext CONNECT header
        hdr[4] = (byte) ((hdr[4] & 0xC0) | TYPE_ACCEPT);   // flip type field -> ACCEPT (keep upper 2 bits)
        hdr[0] = fingerprint(hdr);                          // recompute fingerprint
        byte[] enc = encryptBlock(hdr);
        byte[] out = new byte[16];
        System.arraycopy(enc, 0, out, 0, 8);
        System.arraycopy(connectPkt16, 8, out, 8, 8);      // echo the CONNECT's plaintext payload/token
        return out;
    }

    /**
     * Build a peer-INITIATED CONNECT (us -> client) for the reverse direction of the P2P handshake.
     *   header = [fp][01][session u16 LE][type=0][00][len=0x08][nonce]; payload = 8-byte token.
     * LithTech connected-UDP is symmetric: the client CONNECTs to us (we ACCEPT), and we must also
     * CONNECT to the client (it ACCEPTs) before it considers the session established.
     */
    public byte[] buildConnectToClient(int sessionId, int nonce, byte[] token8) {
        byte[] hdr = new byte[8];
        hdr[1] = 0x01;                                              // flags
        hdr[2] = (byte) sessionId; hdr[3] = (byte) (sessionId >>> 8);
        hdr[4] = 0x00;                                              // type field = CONNECT (0)
        hdr[5] = 0x00;
        hdr[6] = 0x08;                                              // payload length
        hdr[7] = (byte) nonce;
        hdr[0] = fingerprint(hdr);
        byte[] enc = encryptBlock(hdr);
        byte[] out = new byte[16];
        System.arraycopy(enc, 0, out, 0, 8);
        if (token8 != null) System.arraycopy(token8, 0, out, 8, 8);
        return out;
    }

    public static final int SUB_PING = 0x0c;
    public static final int SUB_PONG = 0x0d;   // GUESS: ping subtype + 1 -> confirm exact value w/ Ghidra

    /**
     * Build a PONG for a received PING so the client can measure ms:
     *   mirror the ping header, flip the subtype [6] ping->pong, recompute the fingerprint, encrypt the
     *   header, and ECHO the plaintext payload [8:16] (the client's timestamp) so it can diff send vs recv.
     * A verbatim echo fails because it reads as the peer *pinging us*, not a *response* to our ping.
     */
    public byte[] buildPongFromPing(byte[] pingPkt16, int pongSub) {
        byte[] hdr = decryptHeader(pingPkt16);
        hdr[6] = (byte) pongSub;                    // ping subtype -> pong subtype
        hdr[0] = fingerprint(hdr);                  // recompute fingerprint
        byte[] enc = encryptBlock(hdr);
        byte[] out = new byte[16];
        System.arraycopy(enc, 0, out, 0, 8);
        System.arraycopy(pingPkt16, 8, out, 8, 8);  // echo the timestamp/payload so RTT can be computed
        return out;
    }
}
