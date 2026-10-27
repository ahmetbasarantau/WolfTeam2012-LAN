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
public class SelectCharAck  extends ServerMsg {
    private final short msgId = ServerMsg.CS_CH_SELECTCHAR_ACK;
    
    public SelectCharAck()
    {
        size = 24;
        buffer = ByteBuffer.allocate(size);
    }
    
    public byte [] getData(short seqNum, byte[] _ip)
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
        
        // MATCH-UDP DESTINATION. This 16-byte payload carries [status:1][IP:4 net-order][port:2 net-order][pad].
        // It was pasted from a real Softnyx server capture and still held the DEAD original address
        // AE 8E 76 86 = 174.142.118.134 : 9E FD = 40701 -> the client aimed its match UDP at a machine that no
        // longer exists -> no ping response -> infinite ms -> C114 "Network Error". Point it at OUR live server:
        // 7F 00 00 01 = 127.0.0.1, 9F 03 = htons(40707). (For LAN/Hamachi multiplayer this becomes the host IP.)

        byte b[] = {0x00, _ip[0], _ip[1], _ip[2], _ip[3], (byte)0x9F, 0x03, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
        
        for(int i = 0; i < b.length; i++)
        {
            addByte(b[i]);
        }
        return buffer.array();
    }
}
