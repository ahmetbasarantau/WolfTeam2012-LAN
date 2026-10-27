package wtserver.server;

import wtserver.database.Users;

/** Resolves one authoritative character for a player/team. */
public final class CharacterResolver {
    private CharacterResolver() { }

    public static short resolve(int userId, byte team) {
        short fallback = CharacterFaction.defaultCharForTeam(team);
        try {
            Users users = new Users();
            Users.CharacterLoadout loadout = users.getCharacterLoadout(userId);
            int selectedCharId = (team == 1) ? loadout.blueCharId : loadout.redCharId;
            if (selectedCharId <= 0)
                return fallback;

            // The active loadout stores an item ID, not an equipment-slot number.
            // Verify it is still owned before preview, roster and spawn use it.
            if (!users.getCharacterListIds(userId).contains(selectedCharId))
                return fallback;

            // A character model from the opposite faction is accepted by the
            // inventory UI but spawns with 0 HP. Never put such an ID into the
            // roster/spawn packets; use the team's basic model instead.
            boolean factionOk = (team == 1)
                    ? CharacterFaction.isBlueFaction(selectedCharId)
                    : CharacterFaction.isRedFaction(selectedCharId);
            if (!factionOk) {
                System.out.println("[CHARSELECT] user=" + userId + " team=" + team
                        + " rejected cross/unknown faction char=" + selectedCharId
                        + "; fallback=" + fallback);
                return fallback;
            }

            // CharInfo/CharSave persist the exact selector key-to-item mapping;
            // preview, roster and spawn all consume this same owned selection.
            System.out.println("[CHARSELECT] user=" + userId + " team=" + team
                    + " selectedCharId=" + selectedCharId);
            return (short) selectedCharId;
        } catch (Exception e) {
            System.out.println("[CHARSELECT] user=" + userId + " team=" + team
                    + " resolver error: " + e + "; fallback=" + fallback);
            return fallback;
        }
    }
}
