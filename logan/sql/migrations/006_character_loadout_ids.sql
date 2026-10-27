-- Convert active character selection from transient UI/equipment-slot indices
-- to durable character item IDs. Safe to apply once to an existing database.
USE wolfteam;

ALTER TABLE `character_loadout`
    ADD COLUMN IF NOT EXISTS `red_char_id` INT NOT NULL DEFAULT 0 AFTER `blue_slot`,
    ADD COLUMN IF NOT EXISTS `blue_char_id` INT NOT NULL DEFAULT 0 AFTER `red_char_id`;

-- Keep the legacy columns only for transition compatibility. New writes use
-- the item-ID columns, so these fields must not make INSERT fail.
ALTER TABLE `character_loadout`
    MODIFY COLUMN `red_slot` TINYINT UNSIGNED NOT NULL DEFAULT 0,
    MODIFY COLUMN `blue_slot` TINYINT UNSIGNED NOT NULL DEFAULT 0;

-- 0x143A uses the character-list index, whose ordering is the same as the
-- type=1 inventory stream in CharItemListAck. It is NOT a char_slots index.
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
WHERE l.red_char_id=0 AND l.blue_char_id=0;

-- CharInfoAck supports exactly eight slots. Remove accidental invisible rows.
DELETE FROM `char_slots` WHERE `slot` < 1 OR `slot` > 8;
