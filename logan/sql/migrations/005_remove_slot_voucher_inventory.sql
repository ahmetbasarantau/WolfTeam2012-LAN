-- 10050..10064 are automatically consumed account upgrades.
-- Preserve power_slots and weapon_slot_extensions; remove only stale UI rows.
USE wolfteam;

DELETE FROM inventory
WHERE item_id BETWEEN 10050 AND 10064;
