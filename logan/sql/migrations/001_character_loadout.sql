-- Existing WolfTeam databases: apply once without dropping accounts/inventory.
USE wolfteam;

CREATE TABLE IF NOT EXISTS `character_loadout` (
    `user_id` INT NOT NULL PRIMARY KEY,
    `red_slot` TINYINT UNSIGNED NOT NULL,
    `blue_slot` TINYINT UNSIGNED NOT NULL,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT `fk_character_loadout_user`
        FOREIGN KEY (`user_id`) REFERENCES `users`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8;
