package wtserver;

import java.util.Arrays;

/**
 * LithTech Titan / Jupiter "unconnected" UDP handshake layer.
 *
 * Ground truth: the open-source LithTech Jupiter source port (bibendovsky/ltjs),
 * engine/runtime/kernel/net/src/sys/win/udpdriver.cpp + packet.cpp. The client's
 * match UDP is the engine's connectionless handshake, NOT a Softnyx app packet:
 *
 *   packet = [ uint32 TOKEN ][ 3-bit sub-message ][ payload... ]
 *
 * The bytes are a BITSTREAM packed LSB-first, with 32-bit words emitted little-endian
 * (CPacket_Write::WriteBits: accumulator |= value << bitsAccumulated). That is exactly
 * why the client's packet CC FF 00 00 ... decodes as TOKEN=0x0000FFCC (uint32 LE) + sub=0.
 *
 * WolfTeam-specific values (confirmed / to confirm with Ghidra):
 *   TOKEN = 0x0000FFCC   -> from the live client packet (CC FF 00 00). [confirm]
 *   MSG_BITS = 3, enum QUERY=0/QUERY_RESPONSE=1/CONNECT=2/CONNECT_RESPONSE=3/PING=4/PING_RESPONSE=5
 *                        -> stock ltjs enum; WolfTeam may renumber. [confirm client dispatch]
 *
 * The client sends sub=0 (QUERY) and blocks: a verbatim echo keeps sub=0 (a request),
 * so the client never sees a *RESPONSE and never advances. We must reply with the matching
 * *_RESPONSE sub-message. The CONNECT_RESPONSE accept is the state gate:
 *   client requires: Readuint32()==TOKEN, ReadBits(3)==CONNECT_RESPONSE, Readbool()==true.
 *   -> that serializes to exactly: CC FF 00 00 0B
 */
public final class LTNet {

    // --- WolfTeam unconnected-handshake constants ---
    public static final long TOKEN = 0x0000FFCCL;   // uint32 LE == CC FF 00 00  (confirm w/ Ghidra)
    public static final int  MSG_BITS = 3;
    public static final int  MSG_QUERY            = 0;
    public static final int  MSG_QUERY_RESPONSE   = 1;
    public static final int  MSG_CONNECT          = 2;
    public static final int  MSG_CONNECT_RESPONSE = 3;
    public static final int  MSG_PING             = 4;
    public static final int  MSG_PING_RESPONSE    = 5;
    public static final int  PING_BITS = 24;        // k_nPing_Bits

    /** Returns true if the datagram begins with the unconnected TOKEN (uint32 LE). */
    public static boolean isUnconnected(byte[] msg) {
        if (msg == null || msg.length < 4) return false;
        long tok = (msg[0] & 0xffL) | ((msg[1] & 0xffL) << 8)
                 | ((msg[2] & 0xffL) << 16) | ((msg[3] & 0xffL) << 24);
        return tok == TOKEN;
    }

    // ---- bit writer: mirrors CPacket_Write (LSB-first, LE words) ----
    public static final class Writer {
        private byte[] buf = new byte[64];
        private int bitPos = 0;

        public Writer writeBits(long value, int nBits) {
            for (int i = 0; i < nBits; i++) {
                int byteIdx = bitPos >> 3;
                if (byteIdx >= buf.length) buf = Arrays.copyOf(buf, buf.length * 2);
                int bit = (int) ((value >> i) & 1L);
                buf[byteIdx] |= (bit << (bitPos & 7));
                bitPos++;
            }
            return this;
        }
        public Writer writeBool(boolean b) { return writeBits(b ? 1 : 0, 1); }
        public Writer writeUInt8(int v)    { return writeBits(v & 0xffL, 8); }
        public Writer writeUInt16(int v)   { return writeBits(v & 0xffffL, 16); }
        public Writer writeUInt32(long v)  { return writeBits(v & 0xffffffffL, 32); }
        public Writer writeString(String s) {           // null-terminated, one byte per char
            if (s != null) for (int i = 0; i < s.length(); i++) writeUInt8(s.charAt(i) & 0xff);
            return writeUInt8(0);
        }
        public byte[] toBytes() { return Arrays.copyOf(buf, (bitPos + 7) >> 3); }
    }

    // ---- bit reader: mirrors CPacket_Read ----
    public static final class Reader {
        private final byte[] buf;
        private int bitPos = 0;
        public Reader(byte[] b) { this.buf = b; }
        public long readBits(int nBits) {
            long v = 0;
            for (int i = 0; i < nBits; i++) {
                int byteIdx = bitPos >> 3;
                int bit = (byteIdx < buf.length) ? ((buf[byteIdx] >> (bitPos & 7)) & 1) : 0;
                v |= ((long) bit) << i;
                bitPos++;
            }
            return v;
        }
        public long readUInt32() { return readBits(32); }
        public void seekTo(int bit) { this.bitPos = bit; }
    }

    // ---- response builders (mirror CUDPDriver::HandleUnconnectedData) ----

    /** CONNECT_RESPONSE. accept=true -> the state gate flips to "connected". Bytes: CC FF 00 00 0B. */
    public static byte[] connectResponse(boolean accept, boolean wrongVersion) {
        Writer w = new Writer();
        w.writeUInt32(TOKEN);
        w.writeBits(MSG_CONNECT_RESPONSE, MSG_BITS);
        w.writeBool(accept);
        if (!accept) w.writeBool(!wrongVersion);   // matches ltjs reject payload
        return w.toBytes();
    }

    /** QUERY_RESPONSE. Fields per stock ltjs; confirm WolfTeam layout w/ Ghidra. */
    public static byte[] queryResponse(String sessionName, int queryNum,
                                       boolean password, int gameType) {
        Writer w = new Writer();
        w.writeUInt32(TOKEN);
        w.writeBits(MSG_QUERY_RESPONSE, MSG_BITS);
        w.writeBool(true);              // correct version
        w.writeString(sessionName);
        w.writeUInt8(queryNum);
        w.writeBool(password);
        w.writeUInt8(gameType);
        return w.toBytes();
    }

    /** PING_RESPONSE echoes the 24-bit ping value back. */
    public static byte[] pingResponse(long pingVal) {
        Writer w = new Writer();
        w.writeUInt32(TOKEN);
        w.writeBits(MSG_PING_RESPONSE, MSG_BITS);
        w.writeBits(pingVal & 0xFFFFFFL, PING_BITS);
        return w.toBytes();
    }

    private LTNet() {}
}
