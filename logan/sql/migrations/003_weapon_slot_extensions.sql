-- Existing WolfTeam databases: apply after 002_power_slots.sql.
USE wolfteam;

CREATE TABLE IF NOT EXISTS `weapon_slot_extensions` (
    `user_id` INT NOT NULL,
    `set_index` TINYINT UNSIGNED NOT NULL,
    `unlocked_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`user_id`, `set_index`),
    CONSTRAINT `fk_weapon_slot_extensions_user`
        FOREIGN KEY (`user_id`) REFERENCES `users`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

INSERT IGNORE INTO `weapon_slot_extensions` (`user_id`, `set_index`)
SELECT `user_id`, `item_id` - 10061
FROM `inventory`
WHERE `item_id` BETWEEN 10061 AND 10064;

DELETE FROM `inventory` WHERE `item_id` BETWEEN 10061 AND 10064;
