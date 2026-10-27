/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

package wtserver.server.channel;

import wtserver.client.ServerMsg;
import java.nio.ByteBuffer;
import java.util.Random;

/**
 * CS_CH_LOGIN_ACK (0x1301) - the channel-login response.
 *
 * REBUILT from Ghidra's static map of the client's 0x1301 handler (FUN_3408cb80, which
 * self-identifies via the "CS_CH_LOGIN_ACK" log string). The OLD version sent a hardcoded
 * 32-byte captured template that was TRUNCATED: it carried only the fixed numeric fields and
 * NONE of the three trailing length-prefixed strings. The client's handler reads the fixed
 * block, then reads three length-prefixed strings (callsign ASCII, a second ASCII string, and
 * a UNICODE/UTF-16 string). With the strings missing, it read length bytes from past the end of
 * the payload, walked into adjacent heap, and produced a garbage (odd) length for the wide
 * string -> its 2-byte null terminator landed misaligned -> wcslen overran the field ->
 * std::wstring::assign got a multi-gigabyte length -> the main thread froze in a giant memcpy.
 *
 * Body layout AFTER the 1-byte status (offsets are into the userinfo struct, for reference):
 *   u16            -> +0xb04
 *   u32            -> +0xc38
 *   u32            -> +0xb08
 *   u32            -> +0xae0
 *   u16            -> +0xb90
 *   u32            -> +0xb28
 *   u8  (bool)     -> vt+0x238
 *   u8             -> vt+0x1b8
 *   u32            -> vt+0x300
 *   u8 len1 + len1 ASCII bytes   -> +0xac4   = callsign
 *   u8 len2 + len2 ASCII bytes   -> +0xad2   = second string (empty for now)
 *   u8 len3 + len3 UNICODE bytes -> +0xb2c   = wide string; len3 MUST be EVEN (UTF-16, 2 bytes/char)
 *
 * The numeric fields' exact stat meaning (level/gp/gold/...) isn't fully mapped yet; we send the
 * ones we can (level) and zeros elsewhere. The CRITICAL correctness requirements are: all three
 * strings are PRESENT and correctly length-prefixed, and the unicode length is EVEN. Empty
 * strings (len=0) trivially satisfy that and avoid the overrun. The callsign is sent real so the
 * channel shows the player's name.
 *
 * @author MSI
 */
public class LoginAck extends ServerMsg {
    private final short msgId = ServerMsg.CS_CH_LOGIN_ACK;
    private String loginName = "";
    private String loginPassword = "";

    // Optional user data to populate the ack (null -> a safe all-zero/empty ack, e.g. on error).
    private String callsign = "";
    private int level = 0, gp = 0, gold = 0, wc = 0, cash = 0;

    // The 4-byte value written at 0x1301 payload[3:7] (the u32 the client reliably echoes into the
    // LAST 4 bytes of its UDP session key). We choose this per-client and store the same value on the
    // Client, then match UDP packets on these 4 bytes. (The first 2 bytes of the client's UDP key are
    // computed client-side from something other than [1:3] - see PROJECT_LOG - so we match on the 4
    // bytes we fully control instead of the full 6.)
    private int udpKey = 0;

    public LoginAck()
    {
        buffer = ByteBuffer.allocate(512);
    }

    public LoginAck(byte recv_buffer [])
    {
        buffer = ByteBuffer.allocate(512);
        short b = recv_buffer[2];
        for(int i = 0; i < b; i++)
        {
            char c = (char) recv_buffer[3 + i];
            // hash1 - read but unused
        }
        short b1 = recv_buffer[3 + b];
        for(int i = 0; i < b1; i++)
        {
            char c = (char) recv_buffer[4 + b + i];
            loginName += c;
        }
        short b2 = recv_buffer[4 + b + b1];
        for(int i = 0; i < b2; i++)
        {
            // OFF-BY-ONE FIX (TrackQ): password starts at 5+b+b1, mirroring the name at 4+b.
            char c = (char) recv_buffer[5 + b + b1 + i];
            loginPassword += c;
        }
    }

    /** Supply the authenticated user's data so the ack carries a real callsign + stats. */
    public void setUserData(String callsign, int level, int gp, int gold, int wc, int cash)
    {
        this.callsign = (callsign == null) ? "" : callsign;
        this.level = level;
        this.gp = gp;
        this.gold = gold;
        this.wc = wc;
        this.cash = cash;
    }

    /** The 4-byte value to write at payload[3:7] - the client echoes it into UDP key[2:6]. */
    public void setUdpKey(int udpKey)
    {
        this.udpKey = udpKey;
    }

    public byte [] getData(short seqNum, byte errorcode, byte [] session_key)
    {
        buffer.position(0);

        // ---- 8-byte header (filled now; pSize + checksum finalized after the body is built) ----
        byte random = (byte) new Random().nextInt();
        addByte(random);                 // [0] random
        addShort(msgId);                 // [1:2] msgId (LE)
        addShort(seqNum);                // [3:4] seq (LE)
        addShort((short) 0);             // [5:6] placeholder for blockCount (set after)
        addByte((byte) 0);               // [7] placeholder for checksum (set after)

        // ---- body ----
        addByte(errorcode);              // status byte (0 = success)

        // Fixed numeric block - EXACTLY the TrackS layout that entered the channel cleanly (do NOT
        // remove fields; the string tail alignment depends on this exact size). The ONLY change from
        // TrackS: payload[3:7] carries our chosen udpKey (the u32 the client echoes into UDP key[2:6])
        // instead of gp, so we can associate the UDP packet on those 4 controlled bytes.
        // [1:3] u16 -> userinfo+0xb04 = the client's SELF-IDENTITY key. The CHARS_ACK roster parser
        // (Ghidra FUN_3418f8f0) matches each per-user sessionId u16 against +0xb04 to find "my" entry
        // and SetMySlot; without a match the host never detects it's the room master -> shows READY
        // instead of START. This MUST equal RoomCharsAck's per-user getSessionId(). We compute it from
        // the SAME session_key passed in here, so the two are guaranteed identical. (Was: (short) level,
        // a best-guess before +0xb04's real purpose was mapped - level is still sent in the roster.)
        short sessionId = (short) ((session_key[0] & 0xff) + (session_key[1] * 0x100));
        addShort(sessionId);             // [1:3]  u16 -> +0xb04  (self-identity; matches RoomCharsAck)
        addInteger(udpKey);              // [3:7]  u32 -> +0xc38   = UDP-key u32 (client echoes to UDP[2:6])
        addInteger(gold);                // [7:11] u32 -> +0xb08
        addInteger(0);                   // [11:15] u32 -> +0xae0   (KEEP - TrackS had it; entered channel)
        addShort((short) 0);             // [15:17] u16 -> +0xb90
        addInteger(wc);                  // [17:21] u32 -> +0xb28
        addByte((byte) 0);               // [21] u8 bool -> vt+0x238
        addByte((byte) 0);               // [22] u8 -> vt+0x1b8
        addInteger(cash);                // [23:27] u32 -> vt+0x300

        // Three length-prefixed strings. CRITICAL: all present; unicode length EVEN.
        // String 1: callsign (ASCII). len = number of bytes = chars.
        byte[] cs = toAscii(callsign);
        addByte((byte) cs.length);
        for (byte x : cs) addByte(x);

        // String 2: second ASCII string - empty for now (len=0).
        addByte((byte) 0);

        // String 3: wide/UNICODE string - empty for now (len=0). len3=0 is EVEN -> safe.
        addByte((byte) 0);

        // ---- pad payload to a 16-byte (AES block) multiple + finalize header ----
        size = (short) buffer.position();
        if ((size - 8) % 16 > 0) {
            size -= (size - 8) % 16;
            size += 16;
        }
        short pSize = (short) ((size - 8) / 16);   // blockCount covering the REAL payload size
        buffer.position(5);
        addShort(pSize);
        byte checksum = 0;
        for (int i = 0; i < 7; i++) checksum += buffer.get(i);
        addByte(checksum);

        return buffer.array();
    }

    private static byte[] toAscii(String s)
    {
        if (s == null) return new byte[0];
        byte[] out = new byte[s.length()];
        for (int i = 0; i < s.length(); i++) out[i] = (byte) (s.charAt(i) & 0xff);
        return out;
    }

    public String getLoginName()
    {
        return loginName;
    }

    public String getLoginPassword()
    {
        return loginPassword;
    }
}
