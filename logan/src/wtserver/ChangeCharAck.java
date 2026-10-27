/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

package wtserver.server;

import java.nio.ByteBuffer;
import java.util.Random;
import wtserver.client.ServerMsg;

/**
 * CS_IN_CHANGECHAR_ACK (0x1439) - reply to the client's character-slot assignment (0x1438).
 *
 * When the player equips/assigns characters to their character slots, the client sends 0x1438 and
 * then BLOCKS waiting for this 0x1439 acknowledgement before it will let the UI continue (exit
 * inventory, open shop, etc). Without it the client hangs. This sends a minimal status-OK ack
 * (24-byte frame, empty/zero payload, status byte = 0), which is all the client needs to unblock.
 * Uses the same 24-byte ack shape as the other simple action acks in this codebase (e.g.
 * AdvertAllAck).
 *
 * @author MSI
 */
public class ChangeCharAck extends ServerMsg {
    // 0x1439 - not in ServerMsg's named constants, set directly. (REQ 0x1438 -> ACK 0x1439.)
    private final short msgId = (short) 0x1439;

    public ChangeCharAck()
    {
        size = 24;
        buffer = ByteBuffer.allocate(size);
    }

    public byte [] getData(short seqNum)
    {
        buffer.position(0);
        byte random = (byte) new Random().nextInt();
        addByte(random);
        addShort(msgId);
        addShort(seqNum);
        short pSize = (short) ((size - 8) / 16);
        addShort(pSize);
        byte checksum = 0;
        for(int i = 0; i < 7; i++)
        {
            checksum += buffer.get(i);
        }
        addByte(checksum);
        // status byte (0 = OK) + the rest of the 16-byte payload, all zeros.
        for(int i = buffer.position(); i < buffer.capacity(); i++)
        {
            addByte((byte)0);
        }
        return buffer.array();
    }
}
