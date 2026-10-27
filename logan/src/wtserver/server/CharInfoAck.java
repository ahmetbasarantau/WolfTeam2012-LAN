package wtserver.server;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Random;
import wtserver.client.ServerMsg;
import wtserver.database.Users;

public class CharInfoAck extends ServerMsg {
    private final short msgId = ServerMsg.CS_IN_CHARINFO_ACK;
    private int user_id;

    public CharInfoAck(int user_id)
    {
        this.user_id = user_id;
        size = 8;
        buffer = ByteBuffer.allocate(512);
    }

    public byte [] getData(short seqNum)
    {
        if (wtserver.ServerConfig.CLIENT_2012) {
            byte[] data=wtserver.Packet2012.characterInfo(seqNum,user_id);
            size=(short)data.length;
            return data;
        }
        buffer.position(0);
        byte random = (byte) new Random().nextInt();
        addByte(random);
        addShort(msgId);
        addShort(seqNum);
        addShort((short)0);
        byte checksum = 0;
        addByte(checksum);

        Users users = new Users();
        Users.CharacterLoadout loadout = users.getCharacterLoadout(user_id);
        ArrayList<Integer> installed = new ArrayList<Integer>();
        ArrayList<Integer> owned = users.getCharacterListIds(user_id);

        // 0x1435 is the list of INSTALLED characters, not a mirror of every
        // owned character. Mirroring eight inventory rows made all cards show
        // "Installing" and made RELEASE ineffective. Keep only the two active
        // faction loadout IDs; 0x143A remains the authoritative selector.
        if (loadout.redCharId > 0 && owned.contains(loadout.redCharId)
                && wtserver.server.CharacterFaction.isRedFaction(loadout.redCharId))
            installed.add(loadout.redCharId);
        if (loadout.blueCharId > 0 && owned.contains(loadout.blueCharId)
                && wtserver.server.CharacterFaction.isBlueFaction(loadout.blueCharId)
                && !installed.contains(loadout.blueCharId))
            installed.add(loadout.blueCharId);

        addByte((byte) 0);
        addByte((byte) installed.size());
        addByte((byte) 0x09);
        addByte((byte) 0x01);

        for (int charId : installed)
            addInteger(2000000000 + charId);

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
