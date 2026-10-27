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
public class GetPowerUserInfoAck extends ServerMsg {
    private final short msgId = ServerMsg.CS_CH_GETPOWERUSERINFO_ACK;
    
    public GetPowerUserInfoAck(int userId)
    {
        size = 24;
        buffer = ByteBuffer.allocate(size);
    }
    
    public byte [] getData(short seqNum)
    {
        if (wtserver.ServerConfig.CLIENT_2012) {
            byte[] data=wtserver.Packet2012.powerUser(seqNum);
            size=(short)data.length;
            return data;
        }
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
        // All working server snapshots and successful captures use a reserved,
        // zero-filled 0x1327 body.  Treating the first ten bytes as entitlement
        // flags was an unverified layout guess; the client rejected that body
        // and closed the channel with S01. Slot ownership stays in the
        // power_slots table until the actual account-state field is verified.
        while (buffer.position() < buffer.capacity()) addByte((byte) 0);
        return buffer.array();
    }
}
