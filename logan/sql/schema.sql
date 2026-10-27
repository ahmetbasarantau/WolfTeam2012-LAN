-- ============================================================================
-- WolfTeam Private Server - Complete Database Schema + Seed Data
-- ============================================================================
-- Import via: mysql -u root < wolfteam_db.sql
-- Or via phpMyAdmin: import this file
-- ============================================================================

CREATE DATABASE IF NOT EXISTS wolfteam CHARACTER SET utf8 COLLATE utf8_general_ci;
USE wolfteam;

-- ============================================================================
-- TABLO: users (oyuncu hesapları)
-- ============================================================================
DROP TABLE IF EXISTS `kill_log`;
DROP TABLE IF EXISTS `power_slots`;
DROP TABLE IF EXISTS `weapon_slot_extensions`;
DROP TABLE IF EXISTS `character_loadout`;
DROP TABLE IF EXISTS `character_equipment`;
DROP TABLE IF EXISTS `char_slots`;
DROP TABLE IF EXISTS `equipment`;
DROP TABLE IF EXISTS `inventory`;
DROP TABLE IF EXISTS `channels`;
DROP TABLE IF EXISTS `users`;

CREATE TABLE `users` (
    `id` INT AUTO_INCREMENT PRIMARY KEY,
    `username` VARCHAR(32) NOT NULL UNIQUE,
    `password` VARCHAR(64) NOT NULL,
    `callsign` VARCHAR(32) NOT NULL,
    `level` INT DEFAULT 1,
    `gp` INT DEFAULT 0,
    `gold` INT DEFAULT 0,
    `wc` INT DEFAULT 0,
    `cash` INT DEFAULT 0,
    `kill` INT DEFAULT 0,
    `death` INT DEFAULT 0,
    `last_login` DATETIME DEFAULT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

-- ============================================================================
-- TABLO: inventory (oyuncu envanteri - silahlar & karakterler)
--   type: 0 = weapon, 1 = character
-- ============================================================================
CREATE TABLE `inventory` (
    `id` INT AUTO_INCREMENT PRIMARY KEY,
    `user_id` INT NOT NULL,
    `item_id` INT NOT NULL,
    `type` INT DEFAULT 0,
    `upgrade` INT DEFAULT 0,
    `expiry` VARCHAR(32) DEFAULT 'permanent',
    FOREIGN KEY (`user_id`) REFERENCES `users`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

-- ============================================================================
-- TABLO: equipment (oyuncu ekipman slotları, 0-29 arası)
--   slot mapping: 0=primary, 2=secondary, 4=knife, 5=grenade1, 3=grenade2
--                 24-28=powerups
-- ============================================================================
CREATE TABLE `equipment` (
    `id` INT AUTO_INCREMENT PRIMARY KEY,
    `user_id` INT NOT NULL,
    `slot` INT NOT NULL,
    `item_id` INT NOT NULL DEFAULT 0,
    FOREIGN KEY (`user_id`) REFERENCES `users`(`id`) ON DELETE CASCADE,
    UNIQUE KEY `user_slot` (`user_id`, `slot`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

-- ============================================================================
-- TABLO: char_slots (0x1438 seçim slotları; 8=mavi, 10=kırmızı, 9 wire aggregate)
-- ============================================================================
CREATE TABLE `char_slots` (
    `id` INT AUTO_INCREMENT PRIMARY KEY,
    `user_id` INT NOT NULL,
    `slot` INT NOT NULL,
    `char_id` INT NOT NULL DEFAULT 0,
    FOREIGN KEY (`user_id`) REFERENCES `users`(`id`) ON DELETE CASCADE,
    UNIQUE KEY `user_slot` (`user_id`, `slot`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

-- ============================================================================
-- TABLO: character_loadout (aktif kırmızı/mavi karakter listesi seçimi)
-- 0x143A paketindeki kırmızı/mavi seçimleri Java tarafında karakter item ID'lerine
-- çözülür. Bu tablo roster ve spawn için tek kaynaktır.
-- ============================================================================
CREATE TABLE `character_equipment` (
    `user_id` INT NOT NULL,
    `char_id` INT NOT NULL,
    `slot` TINYINT UNSIGNED NOT NULL,
    `item_id` INT NOT NULL DEFAULT 0,
    PRIMARY KEY (`user_id`, `char_id`, `slot`),
    FOREIGN KEY (`user_id`) REFERENCES `users`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `character_loadout` (
    `user_id` INT NOT NULL PRIMARY KEY,
    `red_char_id` INT NOT NULL,
    `blue_char_id` INT NOT NULL,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (`user_id`) REFERENCES `users`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

-- ============================================================================
-- TABLO: power_slots (satin alinmis, aninda etkinlesen 1-10 guc slotlari)
-- 10050..10059 envanter nesnesi degil, hesap bazli yetkilerdir.
-- ============================================================================
CREATE TABLE `power_slots` (
    `user_id` INT NOT NULL,
    `slot` TINYINT UNSIGNED NOT NULL,
    `unlocked_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`user_id`, `slot`),
    FOREIGN KEY (`user_id`) REFERENCES `users`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

-- Four permanent main-weapon-slot expansions (10061..10064).
CREATE TABLE `weapon_slot_extensions` (
    `user_id` INT NOT NULL,
    `set_index` TINYINT UNSIGNED NOT NULL,
    `unlocked_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`user_id`, `set_index`),
    FOREIGN KEY (`user_id`) REFERENCES `users`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

-- ============================================================================
-- TABLO: kill_log (öldürme kayıtları)
-- ============================================================================
CREATE TABLE `kill_log` (
    `id` INT AUTO_INCREMENT PRIMARY KEY,
    `killer_id` INT NOT NULL,
    `victim_id` INT NOT NULL,
    `timestamp` DATETIME DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (`killer_id`) REFERENCES `users`(`id`) ON DELETE CASCADE,
    FOREIGN KEY (`victim_id`) REFERENCES `users`(`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

-- ============================================================================
-- TABLO: channels (kanal listesi - lobide görünen sunucular)
--   f1-f4: lobi istatistik alanları
--   port: GameServer portu (varsayılan 40708)
-- ============================================================================
CREATE TABLE `channels` (
    `id` INT AUTO_INCREMENT PRIMARY KEY,
    `name` VARCHAR(64) NOT NULL,
    `port` INT NOT NULL DEFAULT 40708,
    `is_open` TINYINT(1) DEFAULT 1,
    `enabled` TINYINT(1) DEFAULT 1,
    `f1` INT DEFAULT 0,
    `f2` INT DEFAULT 10000,
    `f3` INT DEFAULT 0,
    `f4` INT DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

-- ============================================================================
-- SEED DATA: Admin kullanıcı
-- ============================================================================
INSERT INTO `users` (`username`, `password`, `callsign`, `level`, `gp`, `gold`, `wc`, `cash`)
VALUES ('admin', 'admin', 'Admin', 99, 999999, 999999, 999999, 999999);

-- ============================================================================
-- SEED DATA: Admin başlangıç envanteri (silahlar)
--   3001 = Basic Knife (bıçak)
--   1001 = Basic Handgun (tabanca)
--   2001 = Basic Rifle (tüfek)
--   9001 = Grenade (bomba)
-- ============================================================================
INSERT INTO `inventory` (`user_id`, `item_id`, `type`, `upgrade`, `expiry`) VALUES
(1, 3001, 0, 0, 'permanent'),   -- Basic Rifle (Softnyx 2012)
(1, 1001, 0, 0, 'permanent'),   -- Basic Handgun
(1, 2001, 0, 0, 'permanent'),   -- Basic Knife (Softnyx 2012)
(1, 9001, 0, 0, 'permanent');   -- Grenade

-- ============================================================================
-- SEED DATA: Admin başlangıç envanteri (karakterler)
--   30005 = Duane Eddy (Mavi Üniforma / Blue Team)
--   30006 = William Kroll (Kırmızı Üniforma / Red Team)
-- ============================================================================
INSERT INTO `inventory` (`user_id`, `item_id`, `type`, `upgrade`, `expiry`) VALUES
(1, 30005, 1, 0, 'permanent'),  -- Duane Eddy (Blue)
(1, 30006, 1, 0, 'permanent');  -- William Kroll (Red)

-- ============================================================================
-- SEED DATA: Admin ekipman slotları (varsayılan donanım)
--   slot 0 = primary, slot 2 = secondary, slot 4 = knife, slot 5 = grenade1
-- ============================================================================
INSERT INTO `equipment` (`user_id`, `slot`, `item_id`) VALUES
(1, 0, 2000003001),  -- Primary: Basic Rifle
(1, 2, 2000001001),  -- Secondary: Basic Handgun (item 1001 → unique 2000001001)
(1, 4, 2000002001),  -- Knife: Basic Knife
(1, 5, 2000009001);  -- Grenade1: Grenade (item 9001 → unique 2000009001)

-- ============================================================================
-- SEED DATA: Admin karakter slotları
--   slot 1 = Duane Eddy (Blue / 30005)
--   slot 2 = William Kroll (Red / 30006)
-- ============================================================================
INSERT INTO `char_slots` (`user_id`, `slot`, `char_id`) VALUES
(1, 1, 30005),  -- slot 1 = Blue (Duane Eddy)
(1, 2, 30006);  -- slot 2 = Red (William Kroll)

-- ============================================================================
-- SEED DATA: Kanal listesi (lobi)
-- ============================================================================
INSERT INTO `channels` (`name`, `port`, `is_open`, `enabled`, `f1`, `f2`, `f3`, `f4`) VALUES
('New Players Channel', 40708, 1, 1, 0, 10000, 0, 20),
('Global Channel 1',      40708, 1, 1, 0, 10000, 0, 0);
