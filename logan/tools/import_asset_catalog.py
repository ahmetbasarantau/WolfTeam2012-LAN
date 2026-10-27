"""Build a searchable inventory catalog from the installed 2012 wolf.xfs."""
import argparse
import hashlib
import json
from pathlib import Path
import sys
import struct
import zlib

# Python's embeddable Windows distribution runs in isolated mode. In that
# configuration the script's folder is not always added to sys.path, so load
# the sibling XFS helper explicitly instead of relying on the caller's cwd.
sys.path.insert(0, str(Path(__file__).resolve().parent))
from import_client_bundles import extract_set_table


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open('rb') as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def parse_text_records(data: bytes, string_count: int) -> dict[int, list[str]]:
    offset = 0
    records = {}
    while offset + 2 <= len(data):
        item_id, = struct.unpack_from('<H', data, offset)
        offset += 2
        strings = []
        for _ in range(string_count):
            if offset + 2 > len(data):
                break
            length, = struct.unpack_from('<H', data, offset)
            offset += 2
            byte_len = length * 2
            s = data[offset:offset+byte_len].decode('utf-16le', errors='replace').rstrip('\x00')
            offset += byte_len
            strings.append(s.strip())
        if len(strings) == string_count:
            records[item_id] = strings
    return records


CUSTOM_NAMES = {
    1018: "5.56mm Mühimmat",
    10023: "Tam Yükseltme (10 Kullanım)",
    10024: "Tam Yükseltme (30 Kullanım)",
    10032: "Çağrı Rengi Değiştirme",
    10033: "Klan Adı Değiştirme",
    10034: "Takma Ad Değiştirme",
    10035: "Klan Sembolü Değiştirme",
    10036: "Takma Ad Rengi Değiştirme",
    10037: "Öldürme/Ölüm Sıfırlama",
    12205: "Mamut Dövmesi (Sırt)",
    12206: "Alev Dövmesi (Sırt)",
    12207: "Köpekbalığı Dövmesi (Sırt)",
    12208: "Hae Tae Dövmesi (Sırt)",
    12209: "Kılıçdiş Dövmesi (Sırt)",
    19603: "Kurt Yeteneği - Savunma",
    19604: "Kurt Yeteneği - Hız",
    19605: "Kurt Yeteneği - Zıplama",
    30039: "Yiğit Demir EURO 2012",
    30040: "Geremi Song EURO 2012",
    30041: "Illude Vaganova EURO 2012",
    30042: "Tomita Yasunori EURO 2012",
    33096: "EM-L03C Dizlik",
}

CUSTOM_BUNDLE_NAMES = {
    14245: "Christopher Smith - SB Paketi",
    14246: "Pedro Gomes - SK Paketi",
    14247: "Hamadah bin Shabah - YB Paketi",
    14248: "Yiğit Demir - SL Paketi",
    14249: "Geremi Song - BM Paketi",
    14899: "Yiğit Demir Özel Paketi",
}


def extract_entry(archive: Path, wanted: bytes) -> bytes:
    with archive.open('rb') as source:
        directory_offset, = struct.unpack('<I', source.read(4))
        source.seek(directory_offset)
        directory = source.read()
        decoder = zlib.decompressobj()
        header = decoder.decompress(directory[1:])
        if not header.startswith(b'XFS '):
            raise ValueError('Unsupported XFS directory')
        consumed = len(directory) - 1 - len(decoder.unused_data)
        index_offset = directory.index(b'\x78\x9c', 1 + consumed)
        index = zlib.decompress(directory[index_offset:])
        matches = []
        scan = 0
        while True:
            found = index.find(wanted, scan)
            if found < 0:
                break
            if found > 0 and index[found - 1] == len(wanted):
                matches.append(found)
            scan = found + 1
        if len(matches) != 1:
            raise ValueError(f'Expected one exact {wanted.decode()} entry')
        record = matches[0] + len(wanted)
        offset = int.from_bytes(index[record:record + 6], 'little')
        size, packed_size = struct.unpack_from('<II', index, record + 6)
        source.seek(offset)
        packed = source.read(packed_size)
        stream_offset = next((i for i in range(min(16, len(packed) - 1))
                              if packed[i] & 0x0F == 8
                              and ((packed[i] << 8) | packed[i + 1]) % 31 == 0), None)
        if stream_offset is None:
            raise ValueError(f'No zlib stream in {wanted.decode()} record header')
        content_parts = []
        remaining = packed[stream_offset:]
        total_size = 0
        while total_size < size:
            next_offset = next((i for i in range(min(16, len(remaining) - 1))
                                if remaining[i] & 0x0F == 8
                                and ((remaining[i] << 8) | remaining[i + 1]) % 31 == 0), None)
            if next_offset is None:
                raise ValueError(f'Missing zlib block in {wanted.decode()}')
            decoder = zlib.decompressobj()
            block = decoder.decompress(remaining[next_offset:])
            content_parts.append(block)
            content_parts.append(decoder.flush())
            if not decoder.eof:
                raise ValueError(f'Incomplete zlib block in {wanted.decode()}')
            total_size += len(block)
            remaining = decoder.unused_data
        if total_size != size:
            raise ValueError(f'Incorrect uncompressed size in {wanted.decode()}')
        content = b''.join(content_parts)
        if len(content) != size:
            raise ValueError(f'Incomplete {wanted.decode()} table')
        return content


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('archive', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    archive = args.archive.resolve()
    bundle_bytes = extract_set_table(archive)
    if len(bundle_bytes) % 140:
        raise ValueError('Unexpected set table size')
    bundles = {}
    for offset in range(0, len(bundle_bytes), 140):
        values = struct.unpack_from('<35I', bundle_bytes, offset)
        bundle_id = values[0]
        parts = [{'item_id': values[i], 'days': values[i + 1]}
                 for i in range(1, 35, 2) if values[i]]
        if parts:
            bundles[str(bundle_id)] = parts

    catalog = {}

    def add(item_id, source, name=''):
        if not 0 < item_id <= 65535:
            return
        row = catalog.setdefault(item_id, {'id': item_id, 'name': '', 'sources': []})
        if source not in row['sources']:
            row['sources'].append(source)
        if name and not row['name']:
            row['name'] = name

    # Weapon performance records are variable length: 48-byte stats, a UTF-16
    # path length, the path, then 36 bytes of floats. The table has no padding.
    performance = extract_entry(archive, b'itemperformance.dat')
    cursor = 0
    while cursor + 50 <= len(performance):
        item_id, = struct.unpack_from('<I', performance, cursor)
        path_length, = struct.unpack_from('<H', performance, cursor + 48)
        end = cursor + 50 + path_length * 2 + 36
        if path_length > 256 or end > len(performance):
            raise ValueError(f'Invalid itemperformance record at {cursor}')
        path = performance[cursor + 50:cursor + 50 + path_length * 2].decode('utf-16le')
        if not path.startswith('WT_'):
            raise ValueError(f'Unexpected asset path in itemperformance at {cursor}')
        add(item_id, 'weapon-data', Path(path).stem)
        cursor = end
    if cursor != len(performance):
        raise ValueError('Unparsed bytes in itemperformance table')

    characters = extract_entry(archive, b'characterdata.dat')
    if len(characters) % 21:
        raise ValueError('Unexpected characterdata record size')
    character_ids = set()
    for offset in range(0, len(characters), 21):
        item_id, = struct.unpack_from('<H', characters, offset)
        if 30000 < item_id < 31000:
            character_ids.add(item_id)
            add(item_id, 'character-data')

    tattoos = extract_entry(archive, b'abilitytattoo.dat')
    # Tattoo records begin with item ID, model/variant ID, UTF-16 path length,
    # and a path under WT_Chars/SkinsTattoo. Scan only validated path headers.
    for offset in range(0, len(tattoos) - 8):
        item_id, _variant, path_length = struct.unpack_from('<HHH', tattoos, offset)
        if not (10000 <= item_id <= 13000 and 1 <= path_length <= 160):
            continue
        end = offset + 6 + path_length * 2
        if end > len(tattoos):
            continue
        raw_path = tattoos[offset + 6:end]
        if raw_path.startswith('WT_Chars\\SkinsTattoo\\'.encode('utf-16le')):
            path = raw_path.decode('utf-16le', errors='ignore')
            add(item_id, 'tattoo-data', Path(path).stem.removeprefix('Tattoo_'))

    for parts in bundles.values():
        for part in parts:
            add(int(part['item_id']), 'set-data')

    # 2012 shopdata.dat is an array of 326-byte market records; the item code
    # is the second DWORD. This includes individual shop entries absent from sets.
    shop = extract_entry(archive, b'shopdata.dat')
    if len(shop) % 326:
        raise ValueError('Unexpected shopdata record size')
    bundled_ids = {int(bundle_id) for bundle_id in bundles}
    for offset in range(0, len(shop), 326):
        item_id, = struct.unpack_from('<I', shop, offset + 4)
        if item_id not in bundled_ids:
            add(item_id, 'shop-data')

    root_dir = archive.parent
    weapontext_path = root_dir / 'Img' / 'servernotice' / 'weapontext.dat'
    exitemtext_path = root_dir / 'Img' / 'servernotice' / 'exitemtext.dat'

    weapon_names = {}
    if weapontext_path.exists():
        for iid, fields in parse_text_records(weapontext_path.read_bytes(), 5).items():
            weapon_names[iid] = {'name': fields[0], 'type_name': fields[4]}

    exitem_names = {}
    if exitemtext_path.exists():
        for iid, fields in parse_text_records(exitemtext_path.read_bytes(), 3).items():
            exitem_names[iid] = {'name': fields[0]}

    items = []
    for item_id, row in sorted(catalog.items()):
        if 30000 < item_id <= 34000:
            category = 'Karakter' if item_id in character_ids else 'Kıyafet / karakter eşyası'
            item_type = 1
        elif 10000 <= item_id <= 22000:
            category, item_type = 'Güç eşyası', 0
        elif 'weapon-data' in row['sources']:
            category, item_type = 'Silah / ekipman', 0
        else:
            category, item_type = 'Diğer eşya', 0
        row['category'] = category
        row['type'] = item_type
        if item_id in exitem_names and exitem_names[item_id]['name']:
            row['name'] = exitem_names[item_id]['name']
        elif item_id in weapon_names and weapon_names[item_id]['name']:
            row['name'] = weapon_names[item_id]['name']
            if weapon_names[item_id]['type_name']:
                row['type_name'] = weapon_names[item_id]['type_name']
        elif item_id in CUSTOM_NAMES:
            row['name'] = CUSTOM_NAMES[item_id]
        row['label'] = f"{item_id} · {row['name']}" if row['name'] else f'{item_id} · asset'
        items.append(row)

    package_rows = []
    for bundle_id, parts in sorted(bundles.items(), key=lambda x: int(x[0])):
        bid = int(bundle_id)
        if bid in exitem_names and exitem_names[bid]['name']:
            bname = exitem_names[bid]['name']
        elif bid in CUSTOM_BUNDLE_NAMES:
            bname = CUSTOM_BUNDLE_NAMES[bid]
        else:
            bname = f'Paket {bundle_id}'
        package_rows.append({
            'id': bid,
            'name': bname,
            'label': f'{bid} · {bname}',
            'parts': parts,
            'category': 'Paket',
        })
    document = {
        'source': '2012 Softnyx wolf.xfs',
        'archive_sha256': file_sha256(archive),
        'tables': {
            'setitemdata.dat': hashlib.sha256(bundle_bytes).hexdigest(),
            'itemperformance.dat': hashlib.sha256(performance).hexdigest(),
            'characterdata.dat': hashlib.sha256(characters).hexdigest(),
            'abilitytattoo.dat': hashlib.sha256(tattoos).hexdigest(),
            'shopdata.dat': hashlib.sha256(shop).hexdigest(),
        },
        'items': items,
        'bundles': package_rows,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(document, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(f"Imported {len(items)} items and {len(package_rows)} packages from {archive.name}")


if __name__ == '__main__':
    main()
