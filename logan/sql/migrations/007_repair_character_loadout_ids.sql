-- Repair databases where the first ID migration accidentally interpreted
-- 0x143A list indexes as char_slots. Re-derive IDs from CharItemListAck order.
USE wolfteam;

ALTER TABLE `character_loadout`
    MODIFY COLUMN `red_slot` TINYINT UNSIGNED NOT NULL DEFAULT 0,
    MODIFY COLUMN `blue_slot` TINYINT UNSIGNED NOT NULL DEFAULT 0;

UPDATE `character_loadout` l
LEFT JOIN (
    SELECT `user_id`, `item_id`,
           ROW_NUMBER() OVER (PARTITION BY `user_id` ORDER BY `id`) AS list_index
    FROM `inventory`
    WHERE `type`=1
) r ON r.user_id=l.user_id AND r.list_index=l.red_slot
LEFT JOIN (
    SELECT `user_id`, `item_id`,
           ROW_NUMBER() OVER (PARTITION BY `user_id` ORDER BY `id`) AS list_index
    FROM `inventory`
    WHERE `type`=1
) b ON b.user_id=l.user_id AND b.list_index=l.blue_slot
SET l.red_char_id=COALESCE(r.item_id, 0),
    l.blue_char_id=COALESCE(b.item_id, 0)
WHERE l.red_slot > 0 OR l.blue_slot > 0;
