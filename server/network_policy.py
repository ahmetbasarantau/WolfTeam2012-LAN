"""Build explicit address complements for per-program Windows firewall blocks."""
import ipaddress
import json
import sys


def denied_ranges(subnet, allow_radmin=False):
    lan = ipaddress.ip_network(subnet, strict=True)
    private = [ipaddress.ip_network(n) for n in ('10.0.0.0/8', '172.16.0.0/12', '192.168.0.0/16')]
    radmin = ipaddress.ip_network('26.0.0.0/8')
    is_radmin = lan.version == 4 and lan.subnet_of(radmin)
    if lan.version != 4 or not (lan == ipaddress.ip_network('127.0.0.0/8') or
                                any(lan.subnet_of(n) for n in private) or
                                (allow_radmin and is_radmin)):
        raise ValueError('Select an RFC1918 IPv4 LAN, 127.0.0.0/8, or a verified Radmin 26.0.0.0/8 route')
    allowed = sorted([ipaddress.ip_network('127.0.0.0/8'), lan], key=lambda n: int(n.network_address))
    cursor, result = 0, []
    for net in allowed:
        first, last = int(net.network_address), int(net.broadcast_address)
        if first > cursor:
            result.append(f'{ipaddress.IPv4Address(cursor)}-{ipaddress.IPv4Address(first-1)}')
        cursor = max(cursor, last + 1)
    if cursor <= 0xffffffff:
        result.append(f'{ipaddress.IPv4Address(cursor)}-255.255.255.255')
    # Permit IPv6 loopback only; block DNS/HTTP/UDP and all other protocols.
    return result + ['::/128', '::2-ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff']


def _flatten_addresses(obj):
    if isinstance(obj, dict):
        if 'value' in obj:
            return _flatten_addresses(obj['value'])
        return []
    if isinstance(obj, (list, tuple, set)):
        result = []
        for item in obj:
            result.extend(_flatten_addresses(item))
        return result
    if isinstance(obj, str):
        return [obj]
    return [str(obj)]


def normalized_ranges(addresses):
    """Compare address sets exactly, including Windows CIDR/range rewrites."""
    intervals = []
    for value in _flatten_addresses(addresses):
        value = value.strip()
        if not value:
            continue
        if '-' in value:
            first, last = map(ipaddress.ip_address, value.split('-', 1))
            if first.version != last.version or int(first) > int(last):
                raise ValueError('Invalid address range')
        else:
            network = ipaddress.ip_network(value, strict=False)
            first, last = network.network_address, network.broadcast_address
        intervals.append((first.version, int(first), int(last)))
    merged = []
    for version, first, last in sorted(intervals):
        if merged and merged[-1][0] == version and first <= merged[-1][2] + 1:
            merged[-1] = (version, merged[-1][1], max(last, merged[-1][2]))
        else:
            merged.append((version, first, last))
    return merged


if __name__ == '__main__':
    if sys.argv[1] == '--subnet':
        subnet = str(ipaddress.ip_network(f'{sys.argv[2]}/{sys.argv[3]}', strict=False))
        denied_ranges(subnet, allow_radmin='--radmin' in sys.argv[4:])
        print(subnet)
    elif sys.argv[1] == '--check-addresses':
        for pair in json.load(sys.stdin):
            if normalized_ranges(pair['expected']) != normalized_ranges(pair['actual']):
                raise SystemExit(f"Firewall address scope changed: {pair['name']}")
    else:
        print(json.dumps(denied_ranges(sys.argv[1], allow_radmin='--radmin' in sys.argv[2:])))
