-- Every local profile gets all ten power slots and all four weapon presets.
-- These are entitlement rows; the wire order remains power slots 1..10 and
-- weapon set indexes 0..3, independently.
USE wolfteam;

INSERT IGNORE INTO power_slots (user_id, slot)
SELECT users.id, slots.slot
FROM users
CROSS JOIN (
    SELECT 1 AS slot UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5
    UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9 UNION ALL SELECT 10
) AS slots;

INSERT IGNORE INTO weapon_slot_extensions (user_id, set_index)
SELECT users.id, sets.set_index
FROM users
CROSS JOIN (
    SELECT 0 AS set_index UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3
) AS sets;
