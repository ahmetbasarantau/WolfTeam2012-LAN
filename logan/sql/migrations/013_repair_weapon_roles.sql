-- Normalize the old rotated role mapping using the 2012 asset ID families.
-- Idempotent: valid canonical equipment stays in the same role on later starts.
USE wolfteam;
DROP TEMPORARY TABLE IF EXISTS lan_weapon_role_repair;
CREATE TEMPORARY TABLE lan_weapon_role_repair AS
SELECT u.id AS user_id, p.preset,
       MAX(CASE WHEN e.slot=p.preset*6 THEN e.item_id ELSE 0 END) AS old_primary,
       MAX(CASE WHEN e.slot=p.preset*6+1 THEN e.item_id ELSE 0 END) AS old_extra,
       MAX(CASE WHEN e.slot=p.preset*6+2 THEN e.item_id ELSE 0 END) AS old_handgun,
       MAX(CASE WHEN e.slot=p.preset*6+4 THEN e.item_id ELSE 0 END) AS old_knife,
       COALESCE((SELECT MIN(2000000000+i.item_id) FROM inventory i
                 WHERE i.user_id=u.id AND i.type=0 AND i.item_id>=3000 AND i.item_id<9000),0) AS fallback_primary,
       COALESCE((SELECT MIN(2000000000+i.item_id) FROM inventory i
                 WHERE i.user_id=u.id AND i.type=0 AND i.item_id>=1000 AND i.item_id<2000),0) AS fallback_handgun,
       COALESCE((SELECT MIN(2000000000+i.item_id) FROM inventory i
                 WHERE i.user_id=u.id AND i.type=0 AND i.item_id>=2000 AND i.item_id<3000),0) AS fallback_knife
FROM users u CROSS JOIN (SELECT 0 AS preset UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3) p
LEFT JOIN equipment e ON e.user_id=u.id AND e.slot>=p.preset*6 AND e.slot<p.preset*6+6
GROUP BY u.id,p.preset;

START TRANSACTION;
INSERT INTO equipment(user_id,slot,item_id)
SELECT user_id,preset*6,CASE
    WHEN old_primary>=2000003000 AND old_primary<2000009000 THEN old_primary
    WHEN old_knife>=2000003000 AND old_knife<2000009000 THEN old_knife
    WHEN old_handgun>=2000003000 AND old_handgun<2000009000 THEN old_handgun
    ELSE fallback_primary END FROM lan_weapon_role_repair
ON DUPLICATE KEY UPDATE item_id=VALUES(item_id);
INSERT INTO equipment(user_id,slot,item_id)
SELECT user_id,preset*6+1,CASE
    WHEN old_extra>=2000003000 AND old_extra<2000009000 THEN old_extra
    ELSE 0 END FROM lan_weapon_role_repair
ON DUPLICATE KEY UPDATE item_id=VALUES(item_id);
INSERT INTO equipment(user_id,slot,item_id)
SELECT user_id,preset*6+2,CASE
    WHEN old_handgun>=2000001000 AND old_handgun<2000002000 THEN old_handgun
    WHEN old_primary>=2000001000 AND old_primary<2000002000 THEN old_primary
    WHEN old_knife>=2000001000 AND old_knife<2000002000 THEN old_knife
    ELSE fallback_handgun END FROM lan_weapon_role_repair
ON DUPLICATE KEY UPDATE item_id=VALUES(item_id);
INSERT INTO equipment(user_id,slot,item_id)
SELECT user_id,preset*6+4,CASE
    WHEN old_knife>=2000002000 AND old_knife<2000003000 THEN old_knife
    WHEN old_handgun>=2000002000 AND old_handgun<2000003000 THEN old_handgun
    WHEN old_primary>=2000002000 AND old_primary<2000003000 THEN old_primary
    ELSE fallback_knife END FROM lan_weapon_role_repair
ON DUPLICATE KEY UPDATE item_id=VALUES(item_id);
COMMIT;
DROP TEMPORARY TABLE lan_weapon_role_repair;
