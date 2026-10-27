USE wolfteam;
CREATE TABLE IF NOT EXISTS character_equipment (
    user_id INT NOT NULL,
    char_id INT NOT NULL,
    slot TINYINT UNSIGNED NOT NULL,
    item_id INT NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id, char_id, slot),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

-- The 2012 character-item stream includes clothing too (native 34089510).
-- Initial bundle expansion put these in the weapon/power stream instead.
UPDATE inventory SET type=1 WHERE item_id>=31000 AND item_id<=34000 AND type<>1;
