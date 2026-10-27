package wtserver.server;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import wtserver.database.Users;

/** Native 341cf05e..341cf0db writes slot + character unique + 16 item uniques. */
public final class CharacterSave2012 {
    // Match the checked CShell free-slot pool in the LAN compatibility module.
    public static final int SLOT_COUNT = 64;
    private static final int ENTRY_SIZE = 69;
    private CharacterSave2012() { }

    /** Returns the selected red/blue pair when the save contains one of each faction. */
    public static int[] save(byte[] payload, int userId) {
        if (payload == null || payload.length < 1) return null;
        int count = payload[0] & 255;
        if (count > (payload.length - 1) / ENTRY_SIZE)
            throw new IllegalArgumentException("Truncated 2012 character save");
        ByteBuffer input = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        input.get();
        Users users = new Users();
        int red = 0, blue = 0;
        boolean ambiguousRed = false, ambiguousBlue = false;
        for (int i = 0; i < count; i++) {
            int slot = input.get() & 255;
            int unique = input.getInt();
            int[] items = new int[16];
            for (int j = 0; j < items.length; j++) items[j] = input.getInt();
            if (slot < 1 || slot > SLOT_COUNT) continue;
            int charId = characterId(unique);
            if (charId == 0) {
                users.removeCharSlot(userId, slot);
                continue;
            }
            // The first unique is the character. items[0] repeats its body;
            // items[1..15] carry its appearance. They are not selector IDs.
            users.setCharSlot(userId, slot, charId);
            users.saveCharacterEntry(userId, slot, charId, items);
            if (CharacterFaction.isRedFaction(charId)) {
                if (red != 0 && red != charId) ambiguousRed = true;
                red = charId;
            } else if (CharacterFaction.isBlueFaction(charId)) {
                if (blue != 0 && blue != charId) ambiguousBlue = true;
                blue = charId;
            }
            System.out.println("[CHARSAVE2012] user=" + userId + " slot=" + slot
                    + " char=" + charId + " equipment=" + java.util.Arrays.toString(items));
        }
        return red > 0 && blue > 0 && !ambiguousRed && !ambiguousBlue
                ? new int[]{red, blue} : null;
    }

    private static int characterId(int unique) {
        return unique >= 2000000000 ? unique - 2000000000 : 0;
    }
}
