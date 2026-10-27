-- Existing WolfTeam databases: apply once after 001_character_loadout.sql.
-- Converts previously mis-stored Power Slot vouchers into durable account
-- entitlements and removes only those voucher rows (10050..10059).
USE wolfteam;

CREATE TABLE IF NOT EXISTS `power_slots` (
    `user_id` INT NOT NULL,
    `slot` TINYINT UNSIGNED NOT NULL,
    `unlocked_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`user_id`, `slot`),
    CONSTRAINT `fk_power_slots_user`
        FOREIGN KEY (`user_id`) REFERENCES `users`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

INSERT IGNORE INTO `power_slots` (`user_id`, `slot`)
SELECT `user_id`, `item_id` - 10049
FROM `inventory`
WHERE `item_id` BETWEEN 10050 AND 10059;

DELETE FROM `inventory` WHERE `item_id` BETWEEN 10050 AND 10059;
