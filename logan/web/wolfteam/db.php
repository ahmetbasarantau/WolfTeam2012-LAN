<?php
// Private API: PHP itself is bound to loopback by host.py.
$pdo = new PDO('mysql:host=127.0.0.1;port=13306;dbname=wolfteam;charset=utf8mb4', 'root', '', [
    PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
    PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
]);
