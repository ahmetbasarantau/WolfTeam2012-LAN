-- Restore the client-visible mirror for account slot upgrades.
-- Run once on an existing database after migrations 002 and 003.  Ownership
-- remains in power_slots / weapon_slot_extensions; inventory rows are only the
-- login-time UI projection required by this client version.
USE wolfteam;

DELETE FROM inventory WHERE item_id BETWEEN 10050 AND 10064;

INSERT INTO inventory (user_id, item_id, type, upgrade, expiry)
SELECT user_id, 10049 + slot, 0, 0, 'permanent'
FROM power_slots;

INSERT INTO inventory (user_id, item_id, type, upgrade, expiry)
SELECT user_id, 10061 + set_index, 0, 0, 'permanent'
FROM weapon_slot_extensions;
