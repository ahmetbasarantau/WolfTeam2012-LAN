/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

package wtserver.server.channel;

import java.nio.ByteBuffer;
import java.util.Random;
import wtserver.client.ServerMsg;

/**
 *
 * @author MSI
 */
public class SnsAccountAck extends ServerMsg {
    private final short msgId = ServerMsg.CS_CH_SNSACCOUNT_ACK;
    
    public SnsAccountAck()
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
        
        addByte((byte)0);
        // The 2012 client expects an empty SNS account list. The donor's 2013
        // value (2) is interpreted as a count by the newer client only.
        addByte((byte)0);
        
        for(int i = buffer.position(); i < buffer.capacity(); i++)
        {
            addByte((byte)0);
        }
        return buffer.array();
    }
}
