/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */

package wtserver.database;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.StringTokenizer;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.charset.Charset;

/**
 *
 * @author MSI
 */
public class Users extends DatabaseConnection {
    
    private String weburl;
    
    public Users()
    {
        weburl = webserver + "users.php";
    }
    
    public int getLogin(String user, String password)
    {
        // Local Radmin accounts have no password. The client's opaque token is
        // not plaintext; resolve the correctly decoded player name through the API.
        if (user == null || user.isBlank() || user.length() > 12
                || user.chars().anyMatch(c -> Character.isISOControl(c) || c == ';' || c == '"')
                || !Charset.forName("windows-1254").newEncoder().canEncode(user)) return -1;
        String web = weburl + "?req=login&name=" + URLEncoder.encode(user, StandardCharsets.UTF_8);
        
        String response = getResponse(web);
        
        // Defensive: a null/blank/non-numeric response means the login couldn't be resolved.
        // Return -1 (treated as a failed login by the caller) instead of throwing
        // NumberFormatException, which would crash the message handler.
        if (response == null) {
            return -1;
        }
        try {
            return Integer.parseInt(response.trim());
        } catch (NumberFormatException e) {
            System.out.println("[Users.getLogin] non-numeric response for " + user + ": '" + response + "'");
            return -1;
        }
    }
    
    public class UserInfo {

        public String callsign;
        public int level, gp, gold, wc, cash, kill, death;

        public UserInfo(String callsign, int level, int gp, int gold, int wc, int cash, int kill, int death) {
            this.callsign = callsign;
            this.level = level;
            this.gp = gp;
            this.gold = gold;
            this.wc = wc;
            this.cash = cash;
            this.kill = kill;
            this.death = death;
        }
    }
    
    public UserInfo getUserInfo(int user_id)
    {
        String web = weburl + "?req=get&id=" + user_id;
        
        String response = getResponse(web);
        StringTokenizer st = new StringTokenizer(response, ";");
        
        while(st.hasMoreTokens())
        {
            String callsign = st.nextToken();
            int level = Integer.parseInt(st.nextToken());
            int gp = Integer.parseInt(st.nextToken());
            int gold = Integer.parseInt(st.nextToken());
            int wc = Integer.parseInt(st.nextToken());
            int cash = Integer.parseInt(st.nextToken());
            int kill = Integer.parseInt(st.nextToken());
            int death = Integer.parseInt(st.nextToken());
            
            UserInfo userInfo = new UserInfo(callsign, level, gp, gold, wc, cash, kill, death);
            
            return userInfo;
        }
        
        return null;
    }  
    
        public class Item {
        public int id;
        public int type;
        public int upgrade;
        public String expiry;

        public Item(int id, int type, int upgrade, String expiry) {
            this.id = id;
            this.type = type;
            this.upgrade = upgrade;
            this.expiry = expiry;
        }
    }
    public ArrayList<Item> getItems(int user_id)
    {
        // The database entitlement tables are authoritative, but this client
        // also rebuilds its lock widgets from the EX-item inventory on login.
        // Removing 10050..10064 here made a newly bought slot look open until
        // reconnect and then lock again.  Keep one deterministic, permanent
        // client-visible mirror row per entitlement; never let duplicates
        // accumulate.
        syncSlotVoucherRows(user_id);
        String web = weburl + "?req=getitem&id=" + user_id;
        
        String response = getResponse(web);
        ArrayList<Item> items = new ArrayList<Item>();
        if(response == null)
            return items;
        StringTokenizer st = new StringTokenizer(response, ";");
        while(st.hasMoreTokens())
        {
            int id = Integer.parseInt(st.nextToken());
            int type = Integer.parseInt(st.nextToken());
            int upgrade = Integer.parseInt(st.nextToken());
            String expiry = st.nextToken();

            items.add(new Item(id, type, upgrade, expiry));
        }

        return items;
    } 
    
    public void addItems(int user_id, int item_id, int itemType)
    {
        String web = weburl + "?req=additem&id=" + user_id + "&item_id=" + item_id + "&type=" + itemType;
        
        String response = getResponse(web);
    }  

    public class PurchaseResult {
        public boolean success;
        public String expiry;
        public int gold, wc, cash;
        public String error;

        public PurchaseResult(boolean success, String expiry, int gold, int wc, int cash, String error) {
            this.success = success;
            this.expiry = expiry;
            this.gold = gold;
            this.wc = wc;
            this.cash = cash;
            this.error = error;
        }
    }

    /**
     * Commit a shop purchase in one database transaction. currency is the
     * verified request code: 1=AP(cash), 2=WC, 4=Gold.
     */
    public PurchaseResult purchase(int user_id, int item_id, int itemType,
                                   int days, int currency, long price)
    {
        String web = weburl + "?req=purchase&id=" + user_id
                + "&item_id=" + item_id + "&type=" + itemType
                + "&days=" + days + "&currency=" + currency + "&price=" + price;
        String response = getResponse(web);
        if (response == null)
            return new PurchaseResult(false, "", 0, 0, 0, "no-response");
        String[] fields = response.trim().split(";", -1);
        if (fields.length >= 5 && "1".equals(fields[0])) {
            try {
                return new PurchaseResult(true, fields[1], Integer.parseInt(fields[2]),
                        Integer.parseInt(fields[3]), Integer.parseInt(fields[4]), "");
            } catch (NumberFormatException ignored) {
                return new PurchaseResult(false, "", 0, 0, 0, "malformed-success");
            }
        }
        String error = fields.length > 1 ? fields[1] : response.trim();
        return new PurchaseResult(false, "", 0, 0, 0, error);
    }
    
    public void setEquip(String field, int value, int user_id)
    {
        String web = weburl + "?req=equip&id=" + user_id + "&field=" + field + "&value=" + value;
        
        String response = getResponse(web);
    }

    /**
     * Synchronize the client-visible slot vouchers from durable entitlement
     * tables.  This is intentionally a projection, not the ownership source:
     * it fixes reconnect lock state while also removing historical duplicates.
     */
    public void syncSlotVoucherRows(int user_id)
    {
        getResponse(weburl + "?req=syncslotvouchers&id=" + user_id);
    }

    /** @deprecated compatibility alias for older callers. */
    @Deprecated
    public void removeSlotVoucherRows(int user_id)
    {
        syncSlotVoucherRows(user_id);
    }

    /** Persist an activated Power Slot (1..10). These are account upgrades,
     * not regular items, so they must survive a reconnect without appearing as
     * duplicate EX inventory entries. */
    public void unlockPowerSlot(int user_id, int slot)
    {
        if (slot < 1 || slot > 10) {
            System.out.println("[Users.unlockPowerSlot] invalid slot " + slot);
            return;
        }
        getResponse(weburl + "?req=unlockpowerslot&id=" + user_id + "&slot=" + slot);
    }

    /** Return the ten persisted entitlement flags, indexed 0..9. */
    public boolean[] getPowerSlots(int user_id)
    {
        boolean[] slots = new boolean[10];
        String response = getResponse(weburl + "?req=getpowerslots&id=" + user_id);
        if (response == null || response.trim().length() == 0)
            return slots;
        StringTokenizer tokens = new StringTokenizer(response, ";");
        while (tokens.hasMoreTokens()) {
            try {
                int slot = Integer.parseInt(tokens.nextToken().trim());
                if (slot >= 1 && slot <= 10) slots[slot - 1] = true;
            } catch (NumberFormatException ignored) {
                System.out.println("[Users.getPowerSlots] malformed response: " + response);
                break;
            }
        }
        return slots;
    }

    /** Persist a main-weapon expansion for one of the four equipment sets. */
    public void unlockWeaponSlotExtension(int user_id, int set)
    {
        if (set < 0 || set > 3) {
            System.out.println("[Users.unlockWeaponSlotExtension] invalid set " + set);
            return;
        }
        getResponse(weburl + "?req=unlockweaponslot&id=" + user_id + "&set=" + set);
    }

    /** Return the four main-weapon expansion flags, indexed by set 0..3. */
    public boolean[] getWeaponSlotExtensions(int user_id)
    {
        boolean[] sets = new boolean[4];
        String response = getResponse(weburl + "?req=getweaponslots&id=" + user_id);
        if (response == null || response.trim().length() == 0)
            return sets;
        StringTokenizer tokens = new StringTokenizer(response, ";");
        while (tokens.hasMoreTokens()) {
            try {
                int set = Integer.parseInt(tokens.nextToken().trim());
                if (set >= 0 && set < 4) sets[set] = true;
            } catch (NumberFormatException ignored) {
                System.out.println("[Users.getWeaponSlotExtensions] malformed response: " + response);
                break;
            }
        }
        return sets;
    }
    
    public int [] getEquipList(int user_id)
    {
        int equips [] = new int [wtserver.server.Equipment2012.DB_SIZE];
        
        String web = weburl + "?req=equiplist&id=" + user_id;
        
        String response = getResponse(web);

        if(response == null)
            return null;
        StringTokenizer st = new StringTokenizer(response, ";");
        int i = 0;
        while(st.hasMoreTokens() && i < equips.length)
        {
            equips[i] = Integer.parseInt(st.nextToken());
            i++;
        }
        
        return equips;
    }
    
    public void registerKill(int killer_id, int dead_id) {
        String web = weburl + "?req=kill&id=" + dead_id + "&value=" + killer_id;

        String response = getResponse(web);
    }
    
    public void addPoints(int user_id, int gold, int gp)
    {
        String web = weburl + "?req=addpoints&id=" + user_id + "&gold=" + gold + "&gp=" + gp;

        String response = getResponse(web);
    }

    // ----------------------------------------------------------------------------
    // Character-slot persistence (the CHARSAVE / 0x1438 state). Per-user: each player
    // has their own slot->char assignments, stored in the char_slots table.
    // ----------------------------------------------------------------------------

    /** Save one character slot for a user. slot = 1-based index, charId = char item code (0 = empty). */
    public void setCharSlot(int user_id, int slot, int charId)
    {
        String web = weburl + "?req=setcharslot&id=" + user_id + "&slot=" + slot + "&char=" + charId;
        getResponse(web);
    }

    /** Remove a character slot (when client clears it). */
    public void removeCharSlot(int user_id, int slot)
    {
        String web = weburl + "?req=removecharslot&id=" + user_id + "&slot=" + slot;
        getResponse(web);
    }

    /** Get all saved character slots for a user as a map of slot -> charId. Empty map if none. */
    public HashMap<Integer, Integer> getCharSlots(int user_id)
    {
        HashMap<Integer, Integer> slots = new HashMap<Integer, Integer>();
        String web = weburl + "?req=getcharslots&id=" + user_id;
        String response = getResponse(web);
        if (response == null || response.length() == 0)
            return slots;
        // response = "slot;char|slot;char|..."  (pipe-separated rows, each 'slot;charId')
        StringTokenizer rows = new StringTokenizer(response, "|");
        while (rows.hasMoreTokens())
        {
            String row = rows.nextToken();
            StringTokenizer f = new StringTokenizer(row, ";");
            if (!f.hasMoreTokens()) continue;
            int slot = Integer.parseInt(f.nextToken().trim());
            int charId = f.hasMoreTokens() ? Integer.parseInt(f.nextToken().trim()) : 0;
            slots.put(slot, charId);
        }
        return slots;
    }

    /** Replace the transient UI keys with exactly the CharInfo entries sent. */
    public void registerCharacterSlots(int userId, Map<Integer, Integer> entries) {
        StringBuilder value = new StringBuilder();
        for (Map.Entry<Integer, Integer> entry : entries.entrySet()) {
            if (value.length() > 0) value.append(',');
            value.append(entry.getKey()).append(':').append(entry.getValue());
        }
        getResponse(weburl + "?req=registercharslots&id=" + userId + "&entries=" + value);
    }

    public void saveCharacterEntry(int userId, int slot, int charId, int[] items) {
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < 16; i++) {
            if (i > 0) value.append(',');
            value.append(items[i]);
        }
        String result = getResponse(weburl + "?req=savecharacterentry&id=" + userId
                + "&slot=" + slot + "&char=" + charId + "&items=" + value);
        if (!"1".equals(result == null ? "" : result.trim()))
            throw new IllegalStateException("Character equipment save failed for user " + userId);
    }

    public Map<Integer, int[]> getCharacterItems(int userId) {
        Map<Integer, int[]> result = new HashMap<Integer, int[]>();
        String response = getResponse(weburl + "?req=getcharacteritems&id=" + userId);
        if (response == null || response.trim().isEmpty()) return result;
        for (String row : response.trim().split("\\|")) {
            String[] fields = row.split(";");
            if (fields.length != 3) continue;
            int charId = Integer.parseInt(fields[0]);
            int slot = Integer.parseInt(fields[1]);
            if (slot < 0 || slot >= 16) continue;
            if (!result.containsKey(charId)) result.put(charId, new int[16]);
            result.get(charId)[slot] = Integer.parseInt(fields[2]);
        }
        return result;
    }

    /** Character IDs advertised by CharItemListAck, used for ownership checks. */
    public ArrayList<Integer> getCharacterListIds(int user_id)
    {
        ArrayList<Integer> ids = new ArrayList<Integer>();
        ArrayList<Item> items = getItems(user_id);
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).type == 1 && items.get(i).id >= 30001 && items.get(i).id < 31000
                    && !ids.contains(items.get(i).id))
                ids.add(items.get(i).id);
        }
        return ids;
    }

    /**
     * Resolve a one-based 0x143A selection slot through the CHARSAVE/CharInfo
     * table. logs8/logs18 prove that 0x143A=04,07 means char_slots 4 and 7;
     * treating those bytes as global inventory-list positions crossed the
     * factions and produced 0-HP characters.
     */
    public int getCharacterIdForSelectionSlot(int user_id, int selectionSlot)
    {
        int limit = wtserver.ServerConfig.CLIENT_2012
                ? wtserver.server.CharacterSave2012.SLOT_COUNT : 10;
        if (selectionSlot < 1 || selectionSlot > limit)
            return 0;
        Integer charId = getCharSlots(user_id).get(selectionSlot);
        return charId == null ? 0 : charId;
    }

    /** Resolve the same byte as a one-based CharItemListAck inventory index. */
    public int getCharacterIdForListIndex(int user_id, int listIndex)
    {
        ArrayList<Integer> ids = getCharacterListIds(user_id);
        if (listIndex < 1 || listIndex > ids.size())
            return 0;
        return ids.get(listIndex - 1);
    }

    /** Active red/blue character item IDs selected by CS_IN_DEFAULTCHAR_REQ (0x143a). */
    public class CharacterLoadout {
        public final int redCharId;
        public final int blueCharId;

        public CharacterLoadout(int redCharId, int blueCharId) {
            this.redCharId = redCharId;
            this.blueCharId = blueCharId;
        }
    }

    /** Persist the two resolved character item IDs (red first, blue second). */
    public void setCharacterLoadout(int user_id, int redCharId, int blueCharId)
    {
        String web = weburl + "?req=setcharloadout&id=" + user_id
                   + "&red_char_id=" + redCharId + "&blue_char_id=" + blueCharId;
        getResponse(web);
    }

    /** Return selected red/blue character IDs, or 0/0 before a player makes a choice. */
    public CharacterLoadout getCharacterLoadout(int user_id)
    {
        String response = getResponse(weburl + "?req=getcharloadout&id=" + user_id);
        if (response == null || response.trim().length() == 0)
            return new CharacterLoadout(0, 0);
        try {
            StringTokenizer fields = new StringTokenizer(response, ";");
            int redCharId = fields.hasMoreTokens() ? Integer.parseInt(fields.nextToken().trim()) : 0;
            int blueCharId = fields.hasMoreTokens() ? Integer.parseInt(fields.nextToken().trim()) : 0;
            return new CharacterLoadout(redCharId, blueCharId);
        } catch (NumberFormatException e) {
            System.out.println("[Users.getCharacterLoadout] malformed response for user " + user_id + ": " + response);
            return new CharacterLoadout(0, 0);
        }
    }
}
