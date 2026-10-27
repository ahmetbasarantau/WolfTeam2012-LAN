/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

package wtserver.server.broker;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Random;
import wtserver.client.ServerMsg;
import wtserver.database.Channels;
import wtserver.database.Channels.Channel;

/**
 * WorldInfoAck (0x1107) - the INFO half of the lobby list: a name + stats per channel.
 * WorldListAck makes the slots; this fills them. They MUST describe the same channels in the
 * same order/count.
 *
 * DB-DRIVEN: builds from Channels.getChannels() - the SAME source/order as WorldListAck - so
 * the counts always match (mismatch crashes the client / leaves "Closed" rows).
 *
 * Per-entry layout, matched to the real capture:
 *   [nameLen:u8]            name length in BYTES (= chars * 2 for UTF-16)
 *   [UTF16LE name]          the channel name
 *   [f1:u16 LE][f2:u16 LE][f3:u8][f4:u8]   stat fields from the DB
 *   Real "New Players Channel": f1=0, f2=10000, f3=0, f4=20.
 *
 * @author MSI
 */
public class WorldInfoAck extends ServerMsg {
    private final short msgId = ServerMsg.CS_BR_WORLDINFO_ACK;

    // ===================================================================
    // EXPERIMENT TOGGLE (Sec 131/133 minimal-list test):
    //   true  -> emit count=1, ONLY the FIRST DB channel. This keeps the count EQUAL to the
    //            minimal WorldListAck (which also sends count=1), so the 0x1101/0x1107 count-
    //            match rule still holds. MUST be set to the SAME value as WorldListAck's flag.
    //   false -> normal: all DB channels.
    // ===================================================================
    private static final boolean MINIMAL_LIST_EXPERIMENT = false;   // FULL DB LIST (all channels)

    public WorldInfoAck()
    {
        size = 8;
        buffer = ByteBuffer.allocate(4096);
    }

    public byte [] getData(short seqNum)
    {
        buffer.position(0);
        byte random = (byte) new Random().nextInt();
        addByte(random);
        addShort(msgId);
        addShort(seqNum);
        addShort((short)0);
        byte checksum = 0;
        addByte(checksum);
        addByte((byte) 0);

        // ----- count: 1-byte (u8), MUST be the same VALUE as WorldListAck's count -----
        Channels channels = new Channels();
        ArrayList<Channel> ch = channels.getChannels();

        // EXPERIMENT: limit to the first channel so the count (1) matches the minimal
        // WorldListAck. Same DB source + same order, just truncated -> counts stay equal.
        int emit = (MINIMAL_LIST_EXPERIMENT && ch.size() > 0) ? 1 : ch.size();

        addByte((byte) emit);
        size += 1;

        // ----- one entry per channel (same list/order as WorldListAck) -----
        for (int i = 0; i < emit; i++) {
            Channel c = ch.get(i);
            String name = c.getChannelName();
            int n = name.length();

            addByte((byte) (n * 2));         // name length in bytes (UTF-16)
            addUnicodeString(name);          // UTF-16LE name
            // f1-f4 as ZEROS (the pre-patch behaviour that had NO errors).
            addShort((short) 0);             // f1 = 0
            addShort((short) 0);             // f2 = 0
            addShort((short) 0);             // f3+f4 = 0 (2 bytes)

            size += 1;        // name length byte
            size += n * 2;    // name bytes
            size += 6;        // f1(2)+f2(2)+f3(1)+f4(1)
        }

        // ----- pad payload to a 16-byte (AES block) multiple + finalize header -----
        size = (short) buffer.position();
        if((size - 8) % 16 > 0)
        {
            size -= (size - 8) % 16;
            size += 16;
        }
        short pSize = (short) ((size - 8) / 16);
        buffer.position(5);
        addShort(pSize);
        for(int i = 0; i < 7; i++)
        {
            checksum += buffer.get(i);
        }
        addByte(checksum);
        return buffer.array();
    }
}
