"""
WolfTeam LAN Server Discovery Module
Scans local subnets for active WolfTeam Broker servers on port 40706
and queries channel/server information using BrokerCodec.
"""

import sys
import os
import socket
import asyncio
import struct
import json
import ipaddress
import argparse
import subprocess
import shutil
import time

# Ensure server path is in sys.path
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
if SCRIPT_DIR not in sys.path:
    sys.path.insert(0, SCRIPT_DIR)

if sys.platform == "win32":
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass

DEFAULT_PORT = 40706
DISCOVERY_PORT = 40705
DISCOVERY_REQUEST = b'WOLFTEAM_2012_LAN_DISCOVER_V1'
DISCOVERY_REPLY = b'WOLFTEAM_2012_LAN_SERVER_V1'


def get_broadcast_targets():
    """Return directed broadcasts for active private and Radmin interfaces."""
    if sys.platform != 'win32':
        return []
    powershell = shutil.which('pwsh.exe') or shutil.which('powershell.exe')
    if not powershell:
        return []
    script = r'''$rows=@(Get-NetIPConfiguration | Where-Object {$_.NetAdapter.Status -eq 'Up'} | ForEach-Object {
  $c=$_; foreach($a in $c.IPv4Address) {
    [pscustomobject]@{IP=$a.IPAddress; Prefix=[int]$a.PrefixLength; Description=$c.NetAdapter.InterfaceDescription}
  }
}); ConvertTo-Json -InputObject $rows -Compress'''
    try:
        result = subprocess.run([powershell, '-NoProfile', '-NonInteractive', '-Command', script],
                                capture_output=True, text=True, timeout=6,
                                creationflags=0x08000000)
        if result.returncode:
            return []
        rows = json.loads(result.stdout.lstrip('\ufeff'))
        if isinstance(rows, dict):
            rows = [rows]
    except (OSError, subprocess.TimeoutExpired, json.JSONDecodeError, TypeError):
        return []
    targets = set()
    for row in rows:
        try:
            address = ipaddress.ip_address(row['IP'])
            network = ipaddress.ip_interface(f"{address}/{int(row['Prefix'])}").network
            description = row.get('Description', '').lower()
            radmin = 'radmin' in description or 'famatech' in description
            if radmin:
                if address not in ipaddress.ip_network('26.0.0.0/8'):
                    continue
                targets.add(str(network.broadcast_address))
            elif address.is_private and not address.is_loopback:
                targets.add(str(network.broadcast_address))
        except (KeyError, ValueError, TypeError):
            continue
    return sorted(targets)


def discover_by_broadcast(port=DISCOVERY_PORT, timeout=0.65):
    """Find Logan hosts without scanning Radmin's large virtual subnet."""
    targets = get_broadcast_targets()
    if not targets:
        return []
    found = set()
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        sock.settimeout(0.1)
        for target in targets:
            try:
                sock.sendto(DISCOVERY_REQUEST, (target, port))
            except OSError:
                continue
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            try:
                data, peer = sock.recvfrom(256)
                if data.startswith(DISCOVERY_REPLY):
                    found.add(peer[0])
            except socket.timeout:
                continue
            except OSError:
                break
    return sorted(found)


def get_local_subnets():
    """Detect local private IPv4 subnets from network interfaces."""
    subnets = []
    try:
        hostname = socket.gethostname()
        addr_infos = socket.getaddrinfo(hostname, None, socket.AF_INET)
        local_ips = sorted(list(set(info[4][0] for info in addr_infos)))
    except Exception:
        local_ips = []

    for ip_str in local_ips:
        try:
            ip = ipaddress.ip_address(ip_str)
            if ip.is_private and not ip.is_loopback:
                # Use standard /24 for local class C/B/A LAN segments
                net = ipaddress.ip_network(f"{ip_str}/24", strict=False)
                if net not in subnets:
                    subnets.append(net)
        except Exception:
            continue

    return subnets, local_ips


async def check_tcp_port(ip_str, port=DEFAULT_PORT, timeout=0.35):
    """Fast non-blocking TCP connect check."""
    try:
        conn = asyncio.open_connection(ip_str, port)
        reader, writer = await asyncio.wait_for(conn, timeout=timeout)
        writer.close()
        await writer.wait_closed()
        return ip_str
    except Exception:
        return None


def query_server_details(ip_str, port=DEFAULT_PORT, timeout=1.0):
    """Connect via BrokerCodec and query WorldInfo (0x1106) for channel names."""
    sock = None
    try:
        # Discovery itself uses only UDP/TCP sockets. Broker metadata is
        # optional, so join-only installs do not need PyCryptodome.
        from broker_codec import BrokerCodec

        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.settimeout(timeout)
        sock.connect((ip_str, port))
        codec = BrokerCodec()
        codec.write_frame(sock, 0x1106, 0, b"")
        msg_id, seq, payload = codec.read_frame(sock)
        if msg_id == 0x1107 and len(payload) >= 3:
            channel_count = payload[1]
            name_len = payload[2]
            name = payload[3:3 + name_len].decode("utf-16le", errors="replace")
            return {
                "ip": ip_str,
                "port": port,
                "name": name if name else "WolfTeam LAN Sunucusu",
                "channels": channel_count
            }
    except Exception:
        pass
    finally:
        if sock:
            try:
                sock.close()
            except Exception:
                pass

    return {
        "ip": ip_str,
        "port": port,
        "name": "WolfTeam LAN Sunucusu",
        "channels": 1
    }


async def scan_all_subnets(subnets, port=DEFAULT_PORT):
    """Scan all candidate hosts in parallel."""
    tasks = []
    # Always include 127.0.0.1
    tasks.append(check_tcp_port("127.0.0.1", port, timeout=0.2))

    for net in subnets:
        for host in net.hosts():
            host_str = str(host)
            tasks.append(check_tcp_port(host_str, port, timeout=0.35))

    results = await asyncio.gather(*tasks)
    active_ips = [ip for ip in results if ip]
    # Unique preserve order (loopback first if present)
    seen = set()
    unique_active = []
    for ip in active_ips:
        if ip not in seen:
            seen.add(ip)
            unique_active.append(ip)

    return unique_active


def discover_servers(port=DEFAULT_PORT):
    """Perform complete LAN discovery and return list of server details."""
    subnets, _ = get_local_subnets()
    active_ips = set(asyncio.run(scan_all_subnets(subnets, port)))
    active_ips.update(discover_by_broadcast())
    servers = []
    for ip in sorted(active_ips):
        details = query_server_details(ip, port)
        servers.append(details)
    return servers


def main():
    parser = argparse.ArgumentParser(description="WolfTeam LAN Server Discovery")
    parser.add_argument("--json", action="store_true", help="Output JSON array")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT, help="Broker port to scan")
    args = parser.parse_args()

    servers = discover_servers(port=args.port)

    if args.json:
        print(json.dumps(servers, ensure_ascii=False))
        return

    if not servers:
        print("LAN ağında aktif WolfTeam sunucusu bulunamadı.")
        return

    print("LAN Ağında Bulunan WolfTeam Sunucuları:")
    for idx, s in enumerate(servers, 1):
        print(f"  [{idx}] {s['ip']}:{s['port']} - {s['name']} ({s['channels']} Kanal)")


if __name__ == "__main__":
    main()
