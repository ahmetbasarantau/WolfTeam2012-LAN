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
 * CS_IN_DEFAULTCHAR_ACK (0x143b) - reply to the client's DEFAULTCHAR request (0x143a).
 *
 * After a character save/change, the client sends 0x143a (CS_IN_DEFAULTCHAR_REQ) and BLOCKS waiting
 * for this 0x143b ack before it will finish the "leave inventory" transition. Without it the client
 * hangs (can't exit the inventory) - but only after a CHARACTER change, since 0x143a only fires when
 * the default-character state changes (a weapon-only change never sends it). This is the same class
 * of pending-ack the CHARSAVE 0x1439 handles; like that one, the client's handler just needs the ack
 * to arrive (status ok), so a minimal 24-byte status-OK ack unblocks it.
 *
 * @author MSI
 */
public class DefaultCharAck extends ServerMsg {
    // 0x143b - not in ServerMsg's named constants, set directly. (REQ 0x143a -> ACK 0x143b.)
    private final short msgId = (short) 0x143b;

    public DefaultCharAck()
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
        // status byte (0 = OK) + remaining payload zeros. Body is ignored by the client.
        for(int i = buffer.position(); i < buffer.capacity(); i++)
        {
            addByte((byte)0);
        }
        return buffer.array();
    }
}
