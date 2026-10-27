-- Restore the permanent client-visible voucher rows for normalized slot
-- entitlements. Safe to run repeatedly: NOT EXISTS prevents duplicates.
USE wolfteam;

INSERT INTO inventory (user_id, item_id, type, upgrade, expiry)
SELECT p.user_id, 10049 + p.slot, 0, 0, 'permanent'
FROM power_slots p
WHERE NOT EXISTS (
    SELECT 1 FROM inventory i
    WHERE i.user_id = p.user_id AND i.item_id = 10049 + p.slot
);

INSERT INTO inventory (user_id, item_id, type, upgrade, expiry)
SELECT w.user_id, 10061 + w.set_index, 0, 0, 'permanent'
FROM weapon_slot_extensions w
WHERE NOT EXISTS (
    SELECT 1 FROM inventory i
    WHERE i.user_id = w.user_id AND i.item_id = 10061 + w.set_index
);
