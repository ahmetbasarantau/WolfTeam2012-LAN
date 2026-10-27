-- Repair legacy CHARSAVE rows that copied every owned character into the
-- installed-character list. 0x1438 uses selector slot 8 for BLUE and slot 10
-- for RED; slot 9 is an aggregate wire record and must never be persisted.
USE wolfteam;

DELETE FROM `char_slots`
WHERE `slot` NOT IN (8, 10);

INSERT INTO `char_slots` (`user_id`, `slot`, `char_id`)
SELECT `user_id`, 8, `blue_char_id`
FROM `character_loadout`
WHERE `blue_char_id` IS NOT NULL AND `blue_char_id` > 0
ON DUPLICATE KEY UPDATE `char_id` = VALUES(`char_id`);

INSERT INTO `char_slots` (`user_id`, `slot`, `char_id`)
SELECT `user_id`, 10, `red_char_id`
FROM `character_loadout`
WHERE `red_char_id` IS NOT NULL AND `red_char_id` > 0
ON DUPLICATE KEY UPDATE `char_id` = VALUES(`char_id`);

DELETE FROM `char_slots` WHERE `slot` = 9;
