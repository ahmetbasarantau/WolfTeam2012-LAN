package wtserver.server;

import java.nio.ByteBuffer;
import java.util.Random;
import wtserver.client.ServerMsg;

/**
 * AddAISlotAck (CS_FD_ADD_AISLOT_ACK, 0x1675) - the NATIVE AI-slot add.
 *
 * Layout CONFIRMED against the client parser (Ghidra): dispatcher reads payload[0]=RESULT, then
 * FUN_342413c0 reads [+1]=SLOT (FUN_340d01b0 sets active slot), [+2]/[+3] consumed (FUN_340016d0),
 * then FUN_3418e4e0 parses the body from [+4]:
 *   [+4]  -> rec+0x48
 *   [+5]  -> rec+0x70   NAME/TYPE INDEX (client resolves the AI name from its string table by this index)
 *   [+6]  -> rec+0x68   level
 *   [+7]  -> rec+0x04   NAME-PRESENT FLAG - MUST be non-zero or the client skips the string-table name pull
 *   [+8..+0x13] 12 bytes skipped
 *   [+0x14] -> FUN_340d0100
 *   [+0x15] -> rec+0x50  connect/render-state (leave 0; FUN_3415e120 + the 40709 handshake sets it to 3,
 *                        exactly like the working 0x1605 bot we read live)
 *
 * This is the AI-specific path. Real players keep using 0x1605 - untouched.
 */
public class AddAISlotAck extends ServerMsg {
    private final short msgId = ServerMsg.CS_FD_ADD_AISLOT_ACK;
    private final byte slot, team, difficulty, botNumber;

    /** slot = absolute roster slot; team = 0/1;
     *  difficulty = 1..3 (EASY/NORMAL/HARD) -> [+5], picks the aistringtable format string;
     *  botNumber  = the %d shown in the name ("AI EASY &lt;botNumber&gt;") -> [+6]. */
    public AddAISlotAck(byte slot, byte team, byte difficulty, byte botNumber) {
        this.slot = slot; this.team = team; this.difficulty = difficulty; this.botNumber = botNumber;
        size = 8;
        buffer = ByteBuffer.allocate(64);
    }

    public byte [] getData(short seqNum) {
        buffer.position(0);
        byte random = (byte) new Random().nextInt();
        addByte(random);
        addShort(msgId);
        addShort(seqNum);
        addShort((short) 0);           // pSize placeholder
        addByte((byte) 0);             // checksum placeholder

        byte[] b = {
            0x00,          // [+0]  RESULT = 0 (success)
            slot,          // [+1]  absolute slot (FUN_340d01b0 sets active slot)
            0x01,          // [+2]  flag (cVar1) -- retrieved and checked in the FUN_340d0140 bind block; MUST stay a proper flag, not the slot
            slot,          // [+3]  box index -> slot (was team). SURGICAL: only this byte carries the slot now; [+2] stays the flag.
            team,          // [+4]  -> rec+0x48  TEAM. LIVE-CONFIRMED (log: client sends team=1 for BLUE, server seats team 1, but [+4]=(1-team)=0 rendered RED): [+4] uses the client's OWN send encoding (0=red, 1=blue). The (1-team) inversion was WRONG both ways (blue->red, red->blue). Pass team straight through.
            difficulty,    // [+5]  -> rec+0x70  DIFFICULTY LEVEL 1..3 -> picks aistringtable format ("AI EASY %d"). CONFIRMED: 1 rendered "AI EASY".
            botNumber,     // [+6]  -> rec+0x68  the %d in the name. CONFIRMED: 2 rendered "...2". Server feeds a distinct per-bot number.
            0x01,          // [+7]  -> rec+0x04  NAME-PRESENT FLAG (must be != 0 to load the AI name)
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,  // [+8..+0x13] skipped
            0x00,          // [+0x14] -> FUN_340d0100
            0x00           // [+0x15] -> rec+0x50 (handshake sets 3)
        };
        for (int i = 0; i < b.length; i++) addByte(b[i]);

        size = (short) buffer.position();
        if ((size - 8) % 16 > 0) { size -= (size - 8) % 16; size += 16; }
        short pSize = (short) ((size - 8) / 16);
        byte checksum = 0;
        buffer.position(5);
        addShort(pSize);
        for (int i = 0; i < 7; i++) checksum += buffer.get(i);
        addByte(checksum);
        return buffer.array();
    }
}
