/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

package wtserver.server.broker;

import wtserver.client.ServerMsg;
import java.nio.ByteBuffer;
import java.util.Random;

/**
 * ChainListAck (0x1103) - REAL FORMAT (Ghidra-confirmed, PROJECT_LOG Sec 92).
 *
 * The client's 0x1103 parser (FUN_3408a320) expects a FIXED-LENGTH payload:
 *   byte[0]      = count, and it MUST be EXACTLY 0x0e (14). Any other value -> the client
 *                  SKIPS the whole body, leaving bytes unconsumed in the frame -> stream desync
 *                  -> frame-level "garbage" error popups.
 *   byte[1..14]  = one byte per channel (14 channels) = a per-channel status/congestion value
 *                  (empty/normal/busy bar). 0x00 = a fine "all empty/normal" default.
 * Total payload = 15 bytes ([0x0e] + 14 bytes). The request's 16-byte client-ID string is
 * never read by the ack parser, so we ignore it.
 *
 * This replaces the old 16-zero-bytes payload (byte[0]=0x00 != 0x0e -> body skipped -> desync).
 *
 * @author MSI
 */
public class ChainListAck extends ServerMsg {
    private final short msgId = ServerMsg.CS_BR_CHAINLIST_ACK;

    public ChainListAck()
    {
        size = 8;
        buffer = ByteBuffer.allocate(64);
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

        // ----- REAL chainlist payload: [0x0e][14 per-channel status bytes] = 15 bytes -----
        addByte((byte) 0x0e);            // count MUST be 0x0e (14) or the client skips the body
        size += 1;
        for (int i = 0; i < 14; i++) {
            addByte((byte) 0x00);        // per-channel status (0 = empty/normal). 14 channels.
            size += 1;
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
