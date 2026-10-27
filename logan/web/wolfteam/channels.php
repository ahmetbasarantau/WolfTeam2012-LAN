<?php
// ============================================================================
//  channels.php  -  WolfTeam Private Server channel API (DB-driven)
//  ----------------------------------------------------------------------------
//  Called by wtserver's Channels.java. Returns the channel list straight from the
//  'channels' table, so the lobby is managed entirely from the database (phpMyAdmin)
//  - no editing Java files to change channels. Both lobby acks (WorldListAck +
//  WorldInfoAck) build from this ONE source, so their counts can never drift.
//
//  Place at:  htdocs\wolfteam\channels.php
//
//  req=get  ->  per enabled channel, 7 fields separated by ';' :
//                 name ; port ; is_open ; f1 ; f2 ; f3 ; f4 ;
//               (repeating). Channels.java reads these 7 tokens per channel.
//
//  Example response (2 channels):
//     New Players Channel;40707;1;0;10000;0;20;Global Channel 1;40707;1;0;10000;0;0;
// ============================================================================

header('Content-Type: text/plain; charset=utf-8');

try {
    require __DIR__ . '/db.php';
} catch (PDOException $e) {
    // On DB failure, return nothing (Channels.java sees an empty list rather than garbage).
    echo '';
    exit;
}

$req = $_GET['req'] ?? '';

if ($req === 'get') {
    // ORDER BY id ASC keeps the lobby order stable and identical on every call,
    // which keeps WorldListAck and WorldInfoAck in lockstep.
    $stmt = $pdo->query(
        "SELECT name, port, is_open, f1, f2, f3, f4
           FROM channels
          WHERE enabled = 1
          ORDER BY id ASC"
    );
    $rows = $stmt->fetchAll(PDO::FETCH_ASSOC);

    $parts = [];
    foreach ($rows as $r) {
        // 7 fields per channel, fixed order. Channels.java reads exactly these 7.
        $parts[] = $r['name'];
        $parts[] = $r['port'];
        $parts[] = $r['is_open'];
        $parts[] = $r['f1'];
        $parts[] = $r['f2'];
        $parts[] = $r['f3'];
        $parts[] = $r['f4'];
    }
    // Trailing ';' is fine - Channels.java's tokenizer ignores empty trailing tokens.
    echo implode(';', $parts);
}
else {
    echo '';
}
?>
