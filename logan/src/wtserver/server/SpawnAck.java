package wtserver.server;

import java.nio.ByteBuffer;
import java.util.Random;
import wtserver.client.ServerMsg;

/**
 * Per-player spawn/appear message. Fixes the in-field 0-HP / can't-move bug: the client's spawn
 * handler (Ghidra FUN_3423a4c0) builds the local player's character from a char id in this message,
 * and with char id 0 it disables the character (validity -1) -> no model -> 0 HP.
 *
 * Wire layout the client reads (offsets are into the PAYLOAD, i.e. bytes AFTER the 8-byte header):
 *   payload[0x00] = killer slot   (we set == slot so killer==victim -> no phantom kill attributed)
 *   payload[0x01] = player slot/id (client matches this against its own SetMySlot id -> local branch)
 *   payload[0x0b] = kill-attribution gate; kill only fires if ==-1 AND killer!=victim. We leave 0.
 *   payload[0x0c] = alive flag; MUST be 0 (nonzero = dead/no-char). 0 = alive/show-char.
 *   payload[0x12] = CHARACTER ID (u16 LE) -> drives model + HP. The field that was 0 = the bug.
 *   payload[0x1c] = UI marker coords (dword); cosmetic only (world pos comes from map spawn points),
 *                   0 is fine for getting a body/HP.
 *   all other bytes = 0.
 *
 * msgId: one of the family 0x1443 / 0x1445 / 0x1447 / 0x1465 - all route to the SAME per-player
 * handler (FUN_3423a4c0), so any should trigger the show-char path. Starting with 0x1443; if the
 * client ignores it, flip SPAWN_MSGID to the next candidate (server-side test, no re-debug needed).
 */
public class SpawnAck extends ServerMsg {

    // Candidates that all dispatch to FUN_3423a4c0: 0x1443, 0x1445, 0x1447, 0x1465.
    public static short SPAWN_MSGID = 0x1443;

    private final byte gate;
    private final byte slot;
    private final short charId;

    public SpawnAck(byte gate, byte slot, short charId) {
        this.gate = gate;
        this.slot = slot;
        this.charId = charId;
        buffer = ByteBuffer.allocate(64);
    }

    public byte[] getData(short seqNum) {
        buffer.position(0);

        // ---- 8-byte header ----
        byte random = (byte) new Random().nextInt();
        addByte(random);                 // [0] random
        addShort(SPAWN_MSGID);           // [1:3] msgId (LE)
        addShort(seqNum);                // [3:5] seq (LE)
        addShort((short) 0);             // [5:7] pSize placeholder
        addByte((byte) 0);               // [7]   checksum placeholder

        // ---- payload ---- (Binary Sec 284/285, cold read of FUN_3423a4c0)
        // The pump reads the RESULT byte at wire +8 and ADVANCES past it before dispatch, so the spawn
        // handler's param_2 starts at wire +9. Its fields are therefore:
        //   wire +8  = p[0x00] = pump RESULT byte (must be 0, else 4001 before the handler runs)
        //   wire +9  = p[0x01] = handler payload[0] = GATE: remote(bot) spawns need this == room[+0xf9],
        //                        else the body build is skipped silently (roster shows, no body)
        //   wire +10 = p[0x02] = handler payload[1] = slot (client matches vs its own -> local/remote branch)
        //   wire +0x15 = p[0x0d] = handler payload[0xc] = alive (0 = alive/show-char)
        //   wire +0x1b = p[0x13] = handler payload[0x12] = char id (u16 LE) -> model + HP
        byte[] p = new byte[40];
        p[0x00] = 0x00;                          // pump result = success
        p[0x01] = gate;                          // GATE byte (== room[+0xf9]; ignored by the local path)
        p[0x02] = slot;                          // player slot/id
        // p[0x0d] = 0  (alive -> show char)             [default 0]
        p[0x13] = (byte) (charId & 0xff);        // char id low
        p[0x14] = (byte) ((charId >> 8) & 0xff); // char id high
        for (byte b : p) addByte(b);

        // ---- finalize header (pSize + checksum) ----
        size = (short) buffer.position();
        if ((size - 8) % 16 > 0) { size -= (size - 8) % 16; size += 16; }
        short pSize = (short) ((size - 8) / 16);
        buffer.position(5);
        addShort(pSize);
        byte checksum = 0;
        for (int i = 0; i < 7; i++) checksum += buffer.get(i);
        addByte(checksum);
        return buffer.array();
    }
}
