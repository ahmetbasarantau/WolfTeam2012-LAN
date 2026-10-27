<?php
// users.php - WolfTeam Private Server PHP API
// Handles all requests from wtserver Users.java
// Place at: http://localhost/wolfteam/users.php

try {
    require __DIR__ . '/db.php';
} catch (PDOException $e) {
    die('-1');
}

$req = $_GET['req'] ?? '';
// CShell's checked LAN compatibility patch allocates this many character keys.
const CHARACTER_SLOT_COUNT = 64;

// Generated from THIS client's 2012 setitemdata.dat, not the 2013 donor catalog.
function bundleCatalog(): array {
    static $bundles = null;
    if ($bundles === null) {
        $data = json_decode(file_get_contents(__DIR__ . '/client_bundles.json'), true, 512, JSON_THROW_ON_ERROR);
        $bundles = $data['bundles'];
    }
    return $bundles;
}

function bundleContents(int $itemId): ?array {
    return bundleCatalog()[$itemId] ?? null;
}

// The caller owns the transaction. Individual component durations can differ
// (for example a 30-day tattoo accompanied by a 7-day GP bonus).
function grantBundle(PDO $pdo, int $userId, array $contents, DateTimeImmutable $start): string {
    $insert = $pdo->prepare('INSERT INTO inventory (user_id,item_id,type,upgrade,expiry) VALUES (?,?,?,0,?)');
    $latestExpiry = '';
    foreach ($contents as $part) {
        $partId = intval($part['item_id']);
        $expiry = $start->modify('+' . intval($part['days']) . ' days')->format('YmdHis');
        // Native 34089510 routes both characters AND clothing to 0x1437.
        $type = ($partId >= 30001 && $partId <= 34000) ? 1 : 0;
        $insert->execute([$userId, $partId, $type, $expiry]);
        if ($expiry > $latestExpiry) $latestExpiry = $expiry;
    }
    return $latestExpiry;
}

// Older purchases were stored as the pack itself. Expand them exactly once
// when the server next reads inventory, preserving the purchase expiry.
function expandLegacyBundles(PDO $pdo, int $userId): void {
    $catalog = bundleCatalog();
    $codes = implode(',', array_map('intval', array_keys($catalog)));
    $pdo->beginTransaction();
    try {
        $stmt = $pdo->prepare('SELECT id,item_id,expiry FROM inventory WHERE user_id=? AND item_id IN (' . $codes . ') FOR UPDATE');
        $stmt->execute([$userId]);
        $packs = $stmt->fetchAll(PDO::FETCH_ASSOC);
        $delete = $pdo->prepare('DELETE FROM inventory WHERE id=? AND user_id=?');
        foreach ($packs as $pack) {
            $contents = $catalog[intval($pack['item_id'])];
            $maxDays = max(array_column($contents, 'days'));
            $start = new DateTimeImmutable('now');
            // Timed old packs retain their original end date. Old permanent
            // pack placeholders receive the finite durations in the asset table.
            if (preg_match('/^[0-9]{14}$/', $pack['expiry']) && $pack['expiry'] < '20900101000000') {
                $end = DateTimeImmutable::createFromFormat('!YmdHis', $pack['expiry']);
                if ($end !== false) $start = $end->modify('-' . $maxDays . ' days');
            }
            grantBundle($pdo, $userId, $contents, $start);
            $delete->execute([$pack['id'], $userId]);
        }
        $pdo->commit();
    } catch (Throwable $e) {
        if ($pdo->inTransaction()) $pdo->rollBack();
        throw $e;
    }
}

// --------------------------------------------------
// req=login  ->  returns user_id or -1 on fail
// --------------------------------------------------
if ($req === 'login') {
    $name     = $_GET['name']     ?? '';
    $password = $_GET['password'] ?? '';

    // NOTE: Password is NOT checked here because the WolfTeam game client sends an
    // ENCRYPTED password blob (not plaintext). The launcher (api/login.php) handles
    // plaintext authentication separately. For the game server, username-only lookup
    // is the expected behaviour for this protocol version.
    if (!preg_match('/^[A-Za-z0-9_.\[\]-]{1,12}$/', $name)) { echo '-1'; exit; }
    // LAN identity: first login creates the remembered local profile.
    $pdo->beginTransaction();
    $create = $pdo->prepare("INSERT IGNORE INTO users(username,password,callsign,level,gp,gold,wc,cash) VALUES (?,'',?,61,500000,10000000,10000000,10000000)");
    $create->execute([$name, $name]);
    $isNew = $create->rowCount() > 0;
    $stmt = $pdo->prepare("SELECT id FROM users WHERE username=?");
    $stmt->execute([$name]);
    $row = $stmt->fetch(PDO::FETCH_ASSOC);

    if ($row && $isNew) {
        $id = intval($row['id']);
        $insert = $pdo->prepare("INSERT INTO inventory(user_id,item_id,type,upgrade,expiry) VALUES (?,?,?,0,'permanent')");
        foreach ([3001,1001,2001,9001,30005,30006] as $item) $insert->execute([$id,$item,$item >= 30000 ? 1 : 0]);
        $equip = $pdo->prepare("INSERT INTO equipment(user_id,slot,item_id) VALUES (?,?,?)");
        for ($set=0; $set<4; $set++) {
            foreach ([0=>3001,2=>1001,4=>2001,5=>9001] as $slot=>$item) $equip->execute([$id,$set*6+$slot,2000000000+$item]);
        }
        $pdo->prepare('INSERT INTO character_loadout(user_id,red_char_id,blue_char_id) VALUES (?,30006,30005)')->execute([$id]);
        $pdo->prepare('INSERT INTO char_slots(user_id,slot,char_id) VALUES (?,8,30005),(?,10,30006)')->execute([$id,$id]);
    }
    if ($row) {
        // Default all account slots open. Keep weapon preset indexes (0..3)
        // separate from power slot indexes (1..10); packet encoders preserve
        // those two independent wire orders.
        $id = intval($row['id']);
        $powerSlot = $pdo->prepare('INSERT IGNORE INTO power_slots(user_id,slot) VALUES (?,?)');
        for ($slot = 1; $slot <= 10; $slot++) $powerSlot->execute([$id, $slot]);
        $weaponSet = $pdo->prepare('INSERT IGNORE INTO weapon_slot_extensions(user_id,set_index) VALUES (?,?)');
        for ($set = 0; $set < 4; $set++) $weaponSet->execute([$id, $set]);
    }
    $pdo->commit();

    if ($row) {
        $pdo->prepare("UPDATE users SET last_login=NOW() WHERE id=?")->execute([$row['id']]);
        echo $row['id'];
    } else {
        echo '-1';
    }
}

// --------------------------------------------------
// req=get  ->  returns callsign;level;gp;gold;wc;cash;kill;death
// --------------------------------------------------
elseif ($req === 'get') {
    $id = intval($_GET['id'] ?? 0);

    $stmt = $pdo->prepare("SELECT callsign,level,gp,gold,wc,cash,`kill`,`death` FROM users WHERE id=?");
    $stmt->execute([$id]);
    $row = $stmt->fetch(PDO::FETCH_ASSOC);

    if ($row) {
        echo implode(';', $row);
    } else {
        echo '-1';
    }
}

// --------------------------------------------------
// req=getitem  ->  returns item_id;type;upgrade;expiry; (repeating)
// --------------------------------------------------
elseif ($req === 'getitem') {
    $id = intval($_GET['id'] ?? 0);

    if ($id > 0) expandLegacyBundles($pdo, $id);

    $stmt = $pdo->prepare("SELECT item_id,type,upgrade,expiry FROM inventory WHERE user_id=? ORDER BY id ASC");
    $stmt->execute([$id]);
    $rows = $stmt->fetchAll(PDO::FETCH_ASSOC);

    $parts = [];
    foreach ($rows as $row) {
        $parts[] = $row['item_id'] . ';' . $row['type'] . ';' . $row['upgrade'] . ';' . $row['expiry'];
    }
    echo implode(';', $parts);
}

// --------------------------------------------------
// req=additem  ->  adds item to inventory
// --------------------------------------------------
elseif ($req === 'additem') {
    $id      = intval($_GET['id']   ?? 0);
    $item_id = intval($_GET['item_id'] ?? 0);
    $type    = intval($_GET['type'] ?? 0);

    $stmt = $pdo->prepare("INSERT INTO inventory (user_id, item_id, type, upgrade, expiry) VALUES (?,?,?,0,'permanent')");
    $stmt->execute([$id, $item_id, $type]);
    echo '1';
}

// --------------------------------------------------
// req=purchase -> atomically debits the selected currency and grants the
// item/account entitlement. Wire currency codes observed in 0x1404 are:
// 1=AP(cash), 2=WC, 4=Gold.
// --------------------------------------------------
elseif ($req === 'purchase') {
    $id       = intval($_GET['id'] ?? 0);
    $item_id  = intval($_GET['item_id'] ?? 0);
    $type     = intval($_GET['type'] ?? 0);
    $days     = intval($_GET['days'] ?? 0);
    $currency = intval($_GET['currency'] ?? 0);
    $priceRaw = $_GET['price'] ?? '';

    $currencyColumns = [1 => 'cash', 2 => 'wc', 4 => 'gold'];
    if ($id < 1 || $item_id < 1 || $days < 0 || $days > 3650
            || !isset($currencyColumns[$currency])
            || !preg_match('/^[0-9]{1,10}$/', $priceRaw)) {
        echo '0;invalid-request';
    } else {
        $price = intval($priceRaw);
        $column = $currencyColumns[$currency];
        $isPowerSlot = ($item_id >= 10050 && $item_id <= 10059);
        $isWeaponSlot = ($item_id >= 10061 && $item_id <= 10064);
        $expiry = $days === 0
            ? '20991231235959'
            : (new DateTimeImmutable('now'))->modify('+' . $days . ' days')->format('YmdHis');

        $pdo->beginTransaction();
        try {
            $stmt = $pdo->prepare("SELECT gold,wc,cash FROM users WHERE id=? FOR UPDATE");
            $stmt->execute([$id]);
            $balance = $stmt->fetch(PDO::FETCH_ASSOC);
            if (!$balance) {
                throw new RuntimeException('user-not-found');
            }

            // Permanent account upgrades cannot be bought/charged twice.
            if ($isPowerSlot) {
                $slot = $item_id - 10049;
                $owned = $pdo->prepare("SELECT 1 FROM power_slots WHERE user_id=? AND slot=?");
                $owned->execute([$id, $slot]);
                if ($owned->fetchColumn()) throw new RuntimeException('already-owned');
            } elseif ($isWeaponSlot) {
                $set = $item_id - 10061;
                $owned = $pdo->prepare("SELECT 1 FROM weapon_slot_extensions WHERE user_id=? AND set_index=?");
                $owned->execute([$id, $set]);
                if ($owned->fetchColumn()) throw new RuntimeException('already-owned');
            }

            if ($price < 0 || intval($balance[$column]) < $price) {
                throw new RuntimeException('insufficient-funds');
            }
            $stmt = $pdo->prepare("UPDATE users SET `$column`=`$column`-? WHERE id=?");
            $stmt->execute([$price, $id]);

            if ($isPowerSlot) {
                $slot = $item_id - 10049;
                $stmt = $pdo->prepare("INSERT INTO power_slots (user_id,slot) VALUES (?,?)");
                $stmt->execute([$id, $slot]);
            } elseif ($isWeaponSlot) {
                $set = $item_id - 10061;
                $stmt = $pdo->prepare("INSERT INTO weapon_slot_extensions (user_id,set_index) VALUES (?,?)");
                $stmt->execute([$id, $set]);
            } elseif (($contents = bundleContents($item_id)) !== null) {
                $expiry = grantBundle($pdo, $id, $contents, new DateTimeImmutable('now'));
            } else {
                $stmt = $pdo->prepare("INSERT INTO inventory (user_id,item_id,type,upgrade,expiry) VALUES (?,?,?,0,?)");
                $stmt->execute([$id, $item_id, $type, $expiry]);
            }

            $stmt = $pdo->prepare("SELECT gold,wc,cash FROM users WHERE id=?");
            $stmt->execute([$id]);
            $after = $stmt->fetch(PDO::FETCH_ASSOC);
            $pdo->commit();
            echo '1;' . $expiry . ';' . $after['gold'] . ';' . $after['wc'] . ';' . $after['cash'];
        } catch (Throwable $e) {
            if ($pdo->inTransaction()) $pdo->rollBack();
            echo '0;' . $e->getMessage();
        }
    }
}

// --------------------------------------------------
// req=unlockpowerslot -> persists an immediate Power Slot activation.
// Item codes 10050..10059 are one-time account entitlements and must never
// be inserted into inventory as ordinary EX items.
// --------------------------------------------------
elseif ($req === 'unlockpowerslot') {
    $id   = intval($_GET['id'] ?? 0);
    $slot = intval($_GET['slot'] ?? 0);
    if ($id < 1 || $slot < 1 || $slot > 10) {
        echo '0';
    } else {
        $stmt = $pdo->prepare("INSERT IGNORE INTO power_slots (user_id, slot) VALUES (?,?)");
        $stmt->execute([$id, $slot]);
        echo '1';
    }
}

// --------------------------------------------------
// req=getpowerslots -> returns unlocked positions as 1;2;... .
// The binary response encoder deliberately consumes these flags only after
// its field offsets have been verified from the client handler.
// --------------------------------------------------
elseif ($req === 'getpowerslots') {
    $id = intval($_GET['id'] ?? 0);
    $stmt = $pdo->prepare("SELECT slot FROM power_slots WHERE user_id=? ORDER BY slot ASC");
    $stmt->execute([$id]);
    echo implode(';', $stmt->fetchAll(PDO::FETCH_COLUMN));
}

// Synchronize the client-visible EX-item projection of account slot upgrades.
// The entitlement tables remain authoritative.  WolfTeam rebuilds the lock
// widgets from these inventory rows after login, so deleting them causes the
// shop to say "Possessing" while the equipment page locks the slots again.
// Rebuild the small projection atomically to remove old duplicates safely.
elseif ($req === 'syncslotvouchers') {
    $id = intval($_GET['id'] ?? 0);
    if ($id < 1) {
        echo '0';
    } else {
        $pdo->beginTransaction();
        try {
            $stmt = $pdo->prepare("DELETE FROM inventory WHERE user_id=? AND item_id BETWEEN 10050 AND 10064");
            $stmt->execute([$id]);

            $stmt = $pdo->prepare("
                INSERT INTO inventory (user_id, item_id, type, upgrade, expiry)
                SELECT user_id, 10049 + slot, 0, 0, 'permanent'
                FROM power_slots WHERE user_id=? ORDER BY slot
            ");
            $stmt->execute([$id]);

            $stmt = $pdo->prepare("
                INSERT INTO inventory (user_id, item_id, type, upgrade, expiry)
                SELECT user_id, 10061 + set_index, 0, 0, 'permanent'
                FROM weapon_slot_extensions WHERE user_id=? ORDER BY set_index
            ");
            $stmt->execute([$id]);
            $pdo->commit();
            echo '1';
        } catch (Throwable $e) {
            if ($pdo->inTransaction()) $pdo->rollBack();
            echo '0';
        }
    }
}

// Compatibility for an older server binary.  Do not delete the projection.
elseif ($req === 'cleanslotvouchers') {
    $id = intval($_GET['id'] ?? 0);
    if ($id < 1) {
        echo '0';
    } else {
        $_GET['req'] = 'syncslotvouchers';
        $pdo->beginTransaction();
        try {
            $stmt = $pdo->prepare("DELETE FROM inventory WHERE user_id=? AND item_id BETWEEN 10050 AND 10064");
            $stmt->execute([$id]);
            $stmt = $pdo->prepare("INSERT INTO inventory (user_id,item_id,type,upgrade,expiry) SELECT user_id,10049+slot,0,0,'permanent' FROM power_slots WHERE user_id=? ORDER BY slot");
            $stmt->execute([$id]);
            $stmt = $pdo->prepare("INSERT INTO inventory (user_id,item_id,type,upgrade,expiry) SELECT user_id,10061+set_index,0,0,'permanent' FROM weapon_slot_extensions WHERE user_id=? ORDER BY set_index");
            $stmt->execute([$id]);
            $pdo->commit();
            echo '1';
        } catch (Throwable $e) {
            if ($pdo->inTransaction()) $pdo->rollBack();
            echo '0';
        }
    }
}

// --------------------------------------------------
// req=unlockweaponslot / getweaponslots -> four permanent main-weapon
// expansions: 10061=Standard, 10062=WP1, 10063=WP2, 10064=WP3.
// --------------------------------------------------
elseif ($req === 'unlockweaponslot') {
    $id  = intval($_GET['id'] ?? 0);
    $set = intval($_GET['set'] ?? -1);
    if ($id < 1 || $set < 0 || $set > 3) {
        echo '0';
    } else {
        $stmt = $pdo->prepare("INSERT IGNORE INTO weapon_slot_extensions (user_id, set_index) VALUES (?,?)");
        $stmt->execute([$id, $set]);
        echo '1';
    }
}

elseif ($req === 'getweaponslots') {
    $id = intval($_GET['id'] ?? 0);
    $stmt = $pdo->prepare("SELECT set_index FROM weapon_slot_extensions WHERE user_id=? ORDER BY set_index ASC");
    $stmt->execute([$id]);
    echo implode(';', $stmt->fetchAll(PDO::FETCH_COLUMN));
}

// --------------------------------------------------
// req=equip  ->  sets a single equip slot
// field = slot name/number, value = item_id
// --------------------------------------------------
elseif ($req === 'equip') {
    $id    = intval($_GET['id']    ?? 0);
    $field = intval($_GET['field'] ?? 0);
    $value = intval($_GET['value'] ?? 0);

    // Upsert the slot
    $stmt = $pdo->prepare("
        INSERT INTO equipment (user_id, slot, item_id) VALUES (?,?,?)
        ON DUPLICATE KEY UPDATE item_id=?
    ");
    $stmt->execute([$id, $field, $value, $value]);
    echo '1';
}

// --------------------------------------------------
// req=equiplist  ->  returns 39 slot values separated by ;
// --------------------------------------------------
elseif ($req === 'equiplist') {
    $id = intval($_GET['id'] ?? 0);

    $stmt = $pdo->prepare("SELECT slot, item_id FROM equipment WHERE user_id=? ORDER BY slot ASC");
    $stmt->execute([$id]);
    $rows = $stmt->fetchAll(PDO::FETCH_ASSOC);

    // 0..23 = weapon presets, 24..38 = all fifteen 2012 Power positions.
    $slots = array_fill(0, 39, 0);
    foreach ($rows as $row) {
        $slot = intval($row['slot']);
        if ($slot >= 0 && $slot < count($slots)) {
            $slots[$slot] = $row['item_id'];
        }
    }
    echo implode(';', $slots);
}

// --------------------------------------------------
// req=kill  ->  logs a kill and updates kill/death counts
// id = victim, value = killer
// --------------------------------------------------
elseif ($req === 'kill') {
    $victim_id = intval($_GET['id']    ?? 0);
    $killer_id = intval($_GET['value'] ?? 0);

    $pdo->prepare("INSERT INTO kill_log (killer_id, victim_id) VALUES (?,?)")->execute([$killer_id, $victim_id]);
    $pdo->prepare("UPDATE users SET kill=kill+1 WHERE id=?")->execute([$killer_id]);
    $pdo->prepare("UPDATE users SET death=death+1 WHERE id=?")->execute([$victim_id]);
    echo '1';
}

// --------------------------------------------------
// req=addpoints  ->  adds gold and gp after a match
// --------------------------------------------------
elseif ($req === 'addpoints') {
    $id   = intval($_GET['id']   ?? 0);
    $gold = intval($_GET['gold'] ?? 0);
    $gp   = intval($_GET['gp']   ?? 0);

    $stmt = $pdo->prepare("UPDATE users SET gold=gold+?, gp=gp+? WHERE id=?");
    $stmt->execute([$gold, $gp, $id]);
    echo '1';
}

// --------------------------------------------------
// req=removecharslot  ->  removes one character slot (user_id, slot)
// --------------------------------------------------
elseif ($req === 'removecharslot') {
    $id   = intval($_GET['id']   ?? 0);
    $slot = intval($_GET['slot'] ?? 0);

    $stmt = $pdo->prepare("DELETE FROM char_slots WHERE user_id=? AND slot=?");
    $stmt->execute([$id, $slot]);
    echo '1';
}

// --------------------------------------------------
// req=setcharslot  ->  saves one character slot (char=item_id)
// --------------------------------------------------
elseif ($req === 'setcharslot') {
    $id     = intval($_GET['id']   ?? 0);
    $slot   = intval($_GET['slot'] ?? 0);
    $charId = intval($_GET['char'] ?? 0);

    if ($id < 1 || $slot < 1 || $slot > CHARACTER_SLOT_COUNT || $charId < 1) {
        echo '0';
    } else {
        $stmt = $pdo->prepare("
            INSERT INTO char_slots (user_id, slot, char_id) VALUES (?,?,?)
            ON DUPLICATE KEY UPDATE char_id=?
        ");
        $stmt->execute([$id, $slot, $charId, $charId]);
        echo '1';
    }
}

// --------------------------------------------------
// req=getcharslots  ->  returns slot;char_id|slot;char_id|...
// --------------------------------------------------
elseif ($req === 'getcharslots') {
    $id = intval($_GET['id'] ?? 0);

    $stmt = $pdo->prepare("SELECT slot, char_id FROM char_slots WHERE user_id=? ORDER BY slot ASC");
    $stmt->execute([$id]);
    $rows = $stmt->fetchAll(PDO::FETCH_ASSOC);

    $parts = [];
    foreach ($rows as $row) {
        $parts[] = $row['slot'] . ';' . $row['char_id'];
    }
    echo implode('|', $parts);
}

// --------------------------------------------------
// Persist exactly the slot keys advertised by the 2012 CharInfo response.
elseif ($req === 'registercharslots') {
    $id = intval($_GET['id'] ?? 0);
    $entries = explode(',', $_GET['entries'] ?? '');
    $pdo->beginTransaction();
    try {
        $pdo->prepare('DELETE FROM char_slots WHERE user_id=?')->execute([$id]);
        $insert = $pdo->prepare('INSERT INTO char_slots(user_id,slot,char_id) VALUES (?,?,?)');
        foreach ($entries as $entry) {
            $pair = explode(':', $entry);
            if (count($pair) !== 2) continue;
            $slot = intval($pair[0]); $charId = intval($pair[1]);
            if ($id < 1 || $slot < 1 || $slot > CHARACTER_SLOT_COUNT || $charId < 30000 || $charId >= 31000) throw new RuntimeException('invalid-character-slot');
            $insert->execute([$id, $slot, $charId]);
        }
        $pdo->commit();
        echo '1';
    } catch (Throwable $e) {
        if ($pdo->inTransaction()) $pdo->rollBack();
        echo '0';
    }
}

// 2012 CHARSAVE record: slot + character unique + sixteen equipment uniques.
// The second dword is equipment[0], not a replacement character or faction pair.
elseif ($req === 'savecharacterentry') {
    $id = intval($_GET['id'] ?? 0);
    $slot = intval($_GET['slot'] ?? 0);
    $charId = intval($_GET['char'] ?? 0);
    $items = array_map('intval', explode(',', $_GET['items'] ?? ''));
    if ($id < 1 || $slot < 1 || $slot > CHARACTER_SLOT_COUNT || count($items) !== 16) { echo '0'; exit; }
    $pdo->beginTransaction();
    try {
        if ($charId === 0) {
            $pdo->prepare('DELETE FROM char_slots WHERE user_id=? AND slot=?')->execute([$id, $slot]);
        } else {
            $stmt = $pdo->prepare('SELECT item_id FROM inventory WHERE user_id=?');
            $stmt->execute([$id]);
            $owned = array_fill_keys($stmt->fetchAll(PDO::FETCH_COLUMN), true);
            if ($charId < 30000 || $charId >= 31000 || !isset($owned[$charId])) throw new RuntimeException('character-not-owned');
            $pdo->prepare('INSERT INTO char_slots(user_id,slot,char_id) VALUES (?,?,?) ON DUPLICATE KEY UPDATE char_id=VALUES(char_id)')->execute([$id, $slot, $charId]);
            $pdo->prepare('DELETE FROM character_equipment WHERE user_id=? AND char_id=?')->execute([$id, $charId]);
            $insert = $pdo->prepare('INSERT INTO character_equipment(user_id,char_id,slot,item_id) VALUES (?,?,?,?)');
            foreach ($items as $index => $unique) {
                $code = $unique >= 2000000000 ? $unique - 2000000000 : 0;
                if (!isset($owned[$code]) || ($index === 0 && $code !== $charId)) $unique = 0;
                if ($unique) $insert->execute([$id, $charId, $index, $unique]);
            }
        }
        $pdo->commit();
        echo '1';
    } catch (Throwable $e) {
        if ($pdo->inTransaction()) $pdo->rollBack();
        echo '0';
    }
}

elseif ($req === 'getcharacteritems') {
    $id = intval($_GET['id'] ?? 0);
    $stmt = $pdo->prepare('SELECT char_id,slot,item_id FROM character_equipment WHERE user_id=? ORDER BY char_id,slot');
    $stmt->execute([$id]);
    $rows = [];
    foreach ($stmt->fetchAll(PDO::FETCH_ASSOC) as $row) $rows[] = implode(';', $row);
    echo implode('|', $rows);
}

// req=setcharloadout -> saves active red/blue character ITEM IDs.
// 0x143A keys resolve through the exact CharInfo/CharSave slot table.
// --------------------------------------------------
elseif ($req === 'setcharloadout') {
    $id       = intval($_GET['id'] ?? 0);
    $redCharId  = intval($_GET['red_char_id'] ?? 0);
    $blueCharId = intval($_GET['blue_char_id'] ?? 0);

    if ($id < 1 || $redCharId < 1 || $blueCharId < 1) {
        echo '0';
    } else {
        $stmt = $pdo->prepare("
            INSERT INTO character_loadout (user_id, red_char_id, blue_char_id) VALUES (?,?,?)
            ON DUPLICATE KEY UPDATE red_char_id=?, blue_char_id=?
        ");
        $stmt->execute([$id, $redCharId, $blueCharId, $redCharId, $blueCharId]);
        echo '1';
    }
}

// --------------------------------------------------
// req=getcharloadout -> red_char_id;blue_char_id (blank means no selection saved yet)
// --------------------------------------------------
elseif ($req === 'getcharloadout') {
    $id = intval($_GET['id'] ?? 0);
    $stmt = $pdo->prepare("SELECT red_char_id, blue_char_id FROM character_loadout WHERE user_id=?");
    $stmt->execute([$id]);
    $row = $stmt->fetch(PDO::FETCH_ASSOC);
    if ($row) {
        echo $row['red_char_id'] . ';' . $row['blue_char_id'];
    }
}

// Local desktop inventory manager. This API is useful only to loopback callers;
// the host binds PHP to 127.0.0.1 and these writes use the same inventory as the game.
elseif (in_array($req, ['adminusers', 'admininventory', 'adminaddassets', 'adminremoveassets', 'adminclearinventory'], true)) {
    $remote = $_SERVER['REMOTE_ADDR'] ?? '';
    if (!in_array($remote, ['127.0.0.1', '::1'], true)) {
        http_response_code(403);
        echo json_encode(['ok' => false, 'error' => 'loopback-only']);
        exit;
    }
    header('Content-Type: application/json; charset=utf-8');
    if ($req === 'adminusers') {
        echo json_encode($pdo->query('SELECT id,username FROM users ORDER BY username')->fetchAll());
    } elseif ($req === 'admininventory') {
        $id = intval($_GET['id'] ?? 0);
        $stmt = $pdo->prepare('SELECT item_id,type,expiry,COUNT(*) AS quantity FROM inventory WHERE user_id=? GROUP BY item_id,type,expiry ORDER BY item_id');
        $stmt->execute([$id]);
        echo json_encode($stmt->fetchAll());
    } elseif ($req === 'adminremoveassets') {
        $body = json_decode(file_get_contents('php://input'), true);
        $userId = intval($body['user_id'] ?? 0);
        $itemIds = $body['item_ids'] ?? [];
        if ($userId < 1 || !is_array($itemIds) || empty($itemIds)) {
            http_response_code(400);
            echo json_encode(['ok' => false, 'error' => 'invalid-request']);
            exit;
        }
        $cleanIds = [];
        foreach ($itemIds as $id) {
            $clean = filter_var($id, FILTER_VALIDATE_INT);
            if ($clean !== false && $clean > 0 && $clean <= 65535) {
                $cleanIds[] = $clean;
            }
        }
        if (empty($cleanIds)) {
            echo json_encode(['ok' => true, 'removed' => 0]);
            exit;
        }
        $in = implode(',', array_fill(0, count($cleanIds), '?'));
        $stmt = $pdo->prepare("DELETE FROM inventory WHERE user_id=? AND item_id IN ($in)");
        $stmt->execute(array_merge([$userId], $cleanIds));
        echo json_encode(['ok' => true, 'removed' => $stmt->rowCount()]);
        exit;
    } elseif ($req === 'adminclearinventory') {
        $body = json_decode(file_get_contents('php://input'), true);
        $userId = intval($body['user_id'] ?? 0);
        if ($userId < 1) {
            http_response_code(400);
            echo json_encode(['ok' => false, 'error' => 'invalid-request']);
            exit;
        }
        $stmt = $pdo->prepare('DELETE FROM inventory WHERE user_id=?');
        $stmt->execute([$userId]);
        echo json_encode(['ok' => true, 'removed' => $stmt->rowCount()]);
        exit;
    } else {
        $body = json_decode(file_get_contents('php://input'), true);
        $userId = intval($body['user_id'] ?? 0);
        $itemIds = $body['item_ids'] ?? [];
        $bundleIds = $body['bundle_ids'] ?? [];
        if ($userId < 1 || !is_array($itemIds) || !is_array($bundleIds)
                || count($itemIds) + count($bundleIds) > 1500) {
            http_response_code(400);
            echo json_encode(['ok' => false, 'error' => 'invalid-request']);
            exit;
        }
        $exists = $pdo->prepare('SELECT 1 FROM users WHERE id=?');
        $exists->execute([$userId]);
        if (!$exists->fetchColumn()) {
            http_response_code(404);
            echo json_encode(['ok' => false, 'error' => 'user-not-found']);
            exit;
        }
        $bundleCatalog = bundleCatalog();
        $grants = [];
        foreach ($itemIds as $rawId) {
            $itemId = filter_var($rawId, FILTER_VALIDATE_INT);
            if ($itemId === false || $itemId < 1 || $itemId > 65535) continue;
            $grants[$itemId] = null; // null means permanent.
        }
        foreach ($bundleIds as $rawId) {
            $bundleId = strval(intval($rawId));
            if (!isset($bundleCatalog[$bundleId])) continue;
            foreach ($bundleCatalog[$bundleId] as $part) {
                $itemId = intval($part['item_id']);
                $days = intval($part['days']);
                if ($itemId > 0 && in_array($days, [1, 7, 30], true)) {
                    if (!array_key_exists($itemId, $grants)) $grants[$itemId] = $days;
                    elseif ($grants[$itemId] !== null) $grants[$itemId] = max($grants[$itemId], $days);
                }
            }
        }
        $added = 0;
        $alreadyOwned = 0;
        $pdo->beginTransaction();
        try {
            $find = $pdo->prepare('SELECT id,expiry FROM inventory WHERE user_id=? AND item_id=? ORDER BY id FOR UPDATE');
            $insert = $pdo->prepare("INSERT INTO inventory(user_id,item_id,type,upgrade,expiry) VALUES (?,?,?,0,?)");
            $update = $pdo->prepare('UPDATE inventory SET expiry=? WHERE id=? AND user_id=?');
            foreach ($grants as $itemId => $days) {
                $type = ($itemId >= 30001 && $itemId <= 34000) ? 1 : 0;
                $find->execute([$userId, $itemId]);
                $rows = $find->fetchAll();
                $permanent = $days === null;
                if (!$rows) {
                    $expiry = $permanent ? 'permanent' : (new DateTimeImmutable('now'))->modify('+' . $days . ' days')->format('YmdHis');
                    $insert->execute([$userId, $itemId, $type, $expiry]);
                    $added++;
                    continue;
                }
                $hasPermanent = false;
                foreach ($rows as $row) {
                    if ($row['expiry'] === 'permanent') { $hasPermanent = true; break; }
                }
                if ($hasPermanent) {
                    $alreadyOwned++;
                    continue;
                }
                $target = $rows[0];
                $current = $target['expiry'];
                if ($permanent) {
                    if ($current !== 'permanent') {
                        $update->execute(['permanent', $target['id'], $userId]);
                        $added++;
                    }
                    continue;
                }
                $base = preg_match('/^[0-9]{14}$/', $current)
                    ? DateTimeImmutable::createFromFormat('!YmdHis', $current) : false;
                $now = new DateTimeImmutable('now');
                if ($base === false || $base < $now) $base = $now;
                $update->execute([$base->modify('+' . $days . ' days')->format('YmdHis'), $target['id'], $userId]);
                $added++;
            }
            $pdo->commit();
            echo json_encode(['ok' => true, 'added' => $added, 'already_owned' => $alreadyOwned]);
        } catch (Throwable $e) {
            if ($pdo->inTransaction()) $pdo->rollBack();
            http_response_code(500);
            echo json_encode(['ok' => false, 'error' => 'save-failed']);
        }
    }
}

else {
    echo '0';
}
?>
