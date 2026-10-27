package wtserver;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import java.util.HashSet;
import wtserver.database.Users;
import wtserver.server.CharacterResolver;
import wtserver.server.Equipment2012;
import wtserver.server.CharacterSave2012;

/** September 2012 CShell wire layouts. Gameplay/state remain in the donor server.
 * Room header/member widths were checked against native readers 0x342308f0/0x342304e0.
 * The donor's 2013 roster has additional fields and cannot be sent unchanged.
 */
public final class Packet2012 {
    private final ByteBuffer b = ByteBuffer.allocate(16384).order(ByteOrder.LITTLE_ENDIAN);
    private Packet2012(int id, short sequence) {
        b.put((byte)0).putShort((short)id).putShort(sequence).putShort((short)0).put((byte)0);
    }
    public Packet2012 u8(int value) { b.put((byte)value); return this; }
    public Packet2012 u16(int value) { b.putShort((short)value); return this; }
    public Packet2012 u32(int value) { b.putInt(value); return this; }
    private Packet2012 wide(String value) { byte[] s=value.getBytes(StandardCharsets.UTF_16LE); u8(s.length); b.put(s); return this; }
    private Packet2012 ascii(String value) { byte[] s=value.getBytes(StandardCharsets.US_ASCII); u8(s.length); b.put(s); return this; }
    private Packet2012 endpoint(int ip, int port) { u32(ip); u8(port >>> 8); u8(port); return this; }
    public byte[] finish() { return Arrays.copyOf(b.array(), b.position()); }
    public static byte[] status(int id, short seq, int status) { return new Packet2012(id,seq).u8(status).finish(); }
    public static byte[] createRoom(short seq,int number) {
        return new Packet2012(0x160d,seq).u8(0).u32(0).u16(number).u8(0).u8(0).finish();
    }

    public static byte[] characterInfo(short seq, int userId) {
        Users users=new Users();
        int blue=CharacterResolver.resolve(userId,(byte)1)&65535;
        int red=CharacterResolver.resolve(userId,(byte)2)&65535;
        HashSet<Integer> owned=new HashSet<Integer>();
        for (Users.Item item:users.getItems(userId)) owned.add(item.id);
        Map<Integer,int[]> clothing=users.getCharacterItems(userId);
        TreeMap<Integer,Integer> entries=new TreeMap<Integer,Integer>();
        // CharInfo is the installed pair, not the whole owned-character list.
        // Advertising stale saved slots here made the client restore its starter
        // Pedro/Christopher cards after returning from a match.
        Map<Integer,Integer> savedSlots=users.getCharSlots(userId);
        addActiveCharacter(entries,savedSlots,blue);
        addActiveCharacter(entries,savedSlots,red);
        ensureCharacterSlot(entries,blue,red,blue);
        ensureCharacterSlot(entries,red,red,blue);
        // 0x143A echoes these exact keys. Never resolve them as inventory order.
        users.registerCharacterSlots(userId,entries);
        Packet2012 p=new Packet2012(0x1435,seq).u8(0).u8(entries.size());
        for (Map.Entry<Integer,Integer> entry:entries.entrySet()) {
            int id=entry.getValue();
            p.u8(entry.getKey()).u8(id==blue?2:(id==red?1:0)).u32(2000000000+id);
            int[] equipment=clothing.get(id);
            for(int i=0;i<16;i++) {
                int unique=equipment==null?0:equipment[i];
                if(i==0 && unique==0) unique=2000000000+id;
                int code=catalog(unique);
                p.u32(owned.contains(code) && (i!=0 || code==id)?unique:0);
            }
        }
        return p.finish();
    }

    private static void addActiveCharacter(TreeMap<Integer,Integer> entries,
            Map<Integer,Integer> savedSlots,int id) {
        if (id <= 0 || entries.containsValue(id)) return;
        for (Map.Entry<Integer,Integer> saved : new TreeMap<Integer,Integer>(savedSlots).entrySet()) {
            if (saved.getValue() == id && saved.getKey() >= 1 && saved.getKey() <= CharacterSave2012.SLOT_COUNT
                    && !entries.containsKey(saved.getKey())) {
                entries.put(saved.getKey(), id);
                return;
            }
        }
        for (int slot=1; slot<=CharacterSave2012.SLOT_COUNT; slot++) if (!entries.containsKey(slot)) {
            entries.put(slot,id);
            return;
        }
    }

    private static void ensureCharacterSlot(TreeMap<Integer,Integer> entries,int id,int red,int blue) {
        if (entries.containsValue(id)) return;
        if (entries.size()>=CharacterSave2012.SLOT_COUNT) {
            for (int slot:new ArrayList<Integer>(entries.descendingKeySet())) {
                int candidate=entries.get(slot);
                if(candidate!=red && candidate!=blue) { entries.remove(slot); break; }
            }
        }
        for(int slot=1;slot<=CharacterSave2012.SLOT_COUNT;slot++) if(!entries.containsKey(slot)) { entries.put(slot,id); return; }
        throw new IllegalStateException("No free character selection key");
    }

    public static byte[] powerUser(short seq) {
        // Native 0x3410a850 consumes enabled, grade, color, title length, expiry.
        return new Packet2012(0x1327,seq).u8(0).u32(1).u32(1).u32(0xffd9c5b8).u8(0).u32(0x7fffffff).finish();
    }

    public static byte[] equipmentCheck(short seq, int userId) {
        Users users=new Users();
        int[] e=users.getEquipList(userId);
        boolean[] unlocked=users.getWeaponSlotExtensions(userId);
        boolean[] powers=users.getPowerSlots(userId);
        int powerExtensions=0;
        while(powerExtensions<powers.length && powers[powerExtensions]) powerExtensions++;
        Packet2012 p=new Packet2012(0x1419,seq).u8(0).u8(4);
        for(int set=0;set<4;set++) {
            p.u8(set);
            p.b.put(Equipment2012.record(e,set,powerExtensions));
        }
        // 3410b21a clears the active EX entitlement map, then reads count*u16.
        // Merely owning vouchers in 0x1431 does not enable the second main slot.
        int active=0;
        for(boolean value:unlocked) if(value) active++;
        p.u8(active);
        for(int set=0;set<4;set++) if(unlocked[set]) p.u16(10061+set);
        return p.finish();
    }

    public static byte[] purchase(short seq, int code, int days, int currency, String expiry, Users.UserInfo user, boolean success) {
        if(!success) return status(0x1405,seq,1);
        Packet2012 p=new Packet2012(0x1405,seq).u8(0).u32(user.gold).u32(user.cash).u32(2000000000+code).u16(code);
        p.ascii(expiry).u16(days==0?65535:days).u8(0).u8(2).u8(currency);
        return p.finish();
    }

    public static byte[] rooms(short seq, ArrayList<GameServer.Room> rooms) {
        Packet2012 p=new Packet2012(0x1601,seq).u8(0).u8(0).u8(0).u8(rooms.size());
        for(GameServer.Room r:rooms) {
            p.u16(r.getRoomNumber()).wide(r.getRoomTitle()).u8(r.getRoomPassword().isEmpty()?0:1)
             .u8(r.gameStarted?1:0).u8(1).u8(r.getMap()).u32(r.getMode()).u8(r.getWins())
             .u16(r.getTime()).u16(r.getPoints()&255).u8(0).u8(0).u16(0).u8(r.getCapacity()).u8(r.getNumberOfUsers())
             .u8(0).u8(0).u8(0).u8(0).u8(0);
        }
        return p.finish();
    }

    public static byte[] roomRoster(short seq, GameServer.Room room) {
        Packet2012 p=new Packet2012(0x1605,seq).u8(0).u8(room.gameStarted?2:1).u8(room.getRoomMaster())
          .u16(room.getRoomNumber()).wide(room.getRoomTitle()).wide(room.getRoomPassword())
          .u8(room.getMap()).u32(room.getMode()).u8(room.getWins()).u8(0)
          .u16(room.getTime()).u16(room.getPoints()&255).u8(0).u16(0).u8(room.getCapacity())
          .u8(0).u8(0).u8(0).u8(1).u8(room.getUsers().size());
        Users users=new Users();
        for(Client c:room.getUsers()) {
            p.u8(c.getRoomSlot()).u8(c.getRoomTeam()).u8(c.getRoomTeamSlot())
             .u8(c.getRoomSlot()==room.getRoomMaster()?2:0).u16(c.getSessionId()).u8(0).u8(0).u8(c.getUserLevel())
             .u32(0).u32(0).ascii(c.getLoginName()).wide(c.getCallsign()).wide("");
            int[] ep=GameServer.INSTANCE.peerConnBlock(c);
            p.endpoint(ep[0],ep[1]).endpoint(ep[2],ep[3]);
            p.u8(Math.max(1,c.getRoomSlotStatus())).u8(4);
            int[] eq=users.getEquipList(c.getUserId());
            for(int set=0;set<4;set++) {
                p.u8(set);
                int base=set*6;
                // Native 342306f3 reads FIFTEEN POWER item IDs, not six
                // weapons followed by nine powers. 340aa3b0 directly reads
                // indices 0/1/2 as back tattoo/backpack/wolf transformation;
                // remaining powers are found by their item category. Putting
                // knife/extra-main/rifle first hid tattoos and selected wolves
                // while claws happened to work via the category scan.
                for(int i=0;i<Equipment2012.POWERS;i++) p.u16(catalog(eq[Equipment2012.POWER_BASE+i]));
                // The roster reader skips this separate weapon word.
                p.u16(catalog(eq[base]));
            }
            // Native character registry uses red(index1) followed by blue(index0).
            int red=CharacterResolver.resolve(c.getUserId(),(byte)2)&65535;
            int blue=CharacterResolver.resolve(c.getUserId(),(byte)1)&65535;
            Map<Integer,int[]> clothing=users.getCharacterItems(c.getUserId());
            characterAppearance(p,red,clothing.get(red));
            characterAppearance(p,blue,clothing.get(blue));
            p.u16(0).u8(1);
        }
        return p.u8(0).u8(0).finish();
    }
    private static void characterAppearance(Packet2012 p,int charId,int[] equipment) {
        // The first word selects the character template. Subsequent words
        // populate its equipment vector (native 34230818 / 3423082d). Slot 0
        // is the body character again, then fifteen positional clothing slots.
        // Sending only the template left the body/equipment vector empty.
        p.u8(17).u16(charId);
        for(int i=0;i<16;i++) {
            int code=equipment==null?0:catalog(equipment[i]);
            if(i==0) code=charId;
            p.u16(code);
        }
    }
    private static int catalog(int unique) { return unique>=2000000000?unique-2000000000:0; }
}
