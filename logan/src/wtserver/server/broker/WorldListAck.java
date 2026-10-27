/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

package wtserver.server.broker;

import wtserver.client.ServerMsg;
import wtserver.database.Channels;
import wtserver.database.Channels.Channel;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Random;

/**
 * WorldListAck (0x1101) - the STRUCTURAL half of the lobby list: one 15-byte entry per
 * channel (ip|marker|id|open|A|B|C|D). NO names (WorldInfoAck supplies those).
 *
 * DB-DRIVEN: builds from Channels.getChannels() - the SAME source WorldInfoAck uses - so the
 * two acks ALWAYS emit the same count. (Mismatch crashes the client / shows spurious "Closed"
 * rows. Reading one source makes the counts equal by construction.)
 *
 * Per-entry layout (15 bytes), matched to the real capture:
 *   [0:4] ip   = 127.0.0.1 (0x0100007F LE)   - our server
 *   [4]   0x9F marker
 *   [5]   id   = 0x03 + index (sequential channel id)
 *   [6]   open = 0x03 if channel open, 0x00 if closed (from DB is_open)
 *   [7:15] A/B/C/D fields: open channels use the captured pattern 02 00 F4 01 11 00 F4 01;
 *          closed channels use zeros.
 *
 * @author MSI
 */
public class WorldListAck extends ServerMsg {
    private final short msgId = ServerMsg.CS_BR_WORLDLIST_ACK;

    // ===================================================================
    // EXPERIMENT TOGGLE (Sec 131 minimal-list test):
    //   true  -> send count=01 + ONE minimal entry (7F 00 00 01 9F 03 03 + zeros to 15 bytes).
    //            Disambiguates "entry-layout/value bug" vs "payload-size/6-entries bug".
    //   false -> normal DB-driven full list (the original behaviour).
    // Flip this back to false + recompile to restore the real channel list.
    // ===================================================================
    private static final boolean MINIMAL_LIST_EXPERIMENT = false;   // FULL DB LIST (all channels)

    private final int advertisedIp;
    public WorldListAck() { this(wtserver.ServerConfig.ADVERTISED_IP); }
    public WorldListAck(int advertisedIp)
    {
        this.advertisedIp = advertisedIp;
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

        if (MINIMAL_LIST_EXPERIMENT) {
            // ----- MINIMAL-LIST EXPERIMENT: count=01 + ONE entry -----
            // Entry = 7F 00 00 01 9F 03 03 followed by zeros to fill 15 bytes.
            // Payload = 1 (count) + 15 (entry) = 16 bytes = exactly one AES block (blockCount=1),
            // same wire size as the working chainlist. If the client parses this without faulting,
            // the crash was payload-size / extra entries OR the four 2-byte field values; if it
            // still faults, the minimal entry itself (or count handling) is the problem.
            addByte((byte) 0x01);            // 1-byte count = 1
            size += 1;

            size += 15;
            addInteger(advertisedIp);   // [0:4] channel ip = the interface THIS client connected on
            addByte((byte) 0x9F);            // [4]   marker
            addByte((byte) 0x03);            // [5]   id
            addByte((byte) 0x03);            // [6]   open
            addByte((byte) 0); addByte((byte) 0);   // [7:15] zeros (the four 2-byte fields)
            addByte((byte) 0); addByte((byte) 0);
            addByte((byte) 0); addByte((byte) 0);
            addByte((byte) 0); addByte((byte) 0);

        } else {
            // ----- NORMAL: one 15-byte entry per channel (DB-driven) -----
            Channels channels = new Channels();
            ArrayList<Channel> ch = channels.getChannels();
            int count = ch.size();
            addByte((byte) count);   // 1-byte count
            size += 1;

            for (int i = 0; i < count; i++) {
                Channel c = ch.get(i);
                size += 15;

                addInteger(advertisedIp);   // channel ip = the interface THIS client connected on
                addByte((byte) 0x9F);            // marker
                addByte((byte) (0x03 + i));      // sequential channel id
                addByte((byte) (c.isOpen() ? 0x03 : 0x00));  // open/closed from DB

                if (c.isOpen()) {
                    // captured A/B/C/D pattern for an open channel
                    addByte((byte) 0x02); addByte((byte) 0x00);   // A
                    addByte((byte) 0xF4); addByte((byte) 0x01);   // B (500)
                    addByte((byte) 0x11); addByte((byte) 0x00);   // C
                    addByte((byte) 0xF4); addByte((byte) 0x01);   // D (500)
                } else {
                    addByte((byte) 0); addByte((byte) 0);
                    addByte((byte) 0); addByte((byte) 0);
                    addByte((byte) 0); addByte((byte) 0);
                    addByte((byte) 0); addByte((byte) 0);
                }
            }
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
