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
 *
 * @author MSI
 */
public class RelayListAck extends ServerMsg {
    private final short msgId = ServerMsg.CS_BR_RELAYLIST_ACK;
    
    public RelayListAck()
    {
        // 2012 layout: status, reserved, endpoint count, then index + IPv4 + port.
        // Emit two endpoints because WorldListAck advertises two selectable channels.
        size = 40;
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
        addByte((byte) 0);
        // ServerMsg's byte at offset 8 is the 2012 status field. Only append
        // the reserved byte here; appending status again shifts the count and
        // makes the client mark the relay channel as closed.
        addByte((byte) 0); // reserved
        addInteger(2);
        byte[] ip = wtserver.ServerConfig.advertisedIpBytes();
        for (int index = 0; index < 2; index++) {
            addByte((byte) index);
            for (byte octet : ip) addByte(octet);
            addShort(htons(40707));
        }
        for(int i = buffer.position(); i < buffer.capacity(); i++)
        {
            addByte((byte)0);
        }
        return buffer.array();
    }
}
