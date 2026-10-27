"""Read the installed 2012 XFS set table; never modify client assets."""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import zlib


def extract_set_table(archive: Path) -> bytes:
    with archive.open('rb') as source:
        directory_offset = struct.unpack('<I', source.read(4))[0]
        source.seek(directory_offset)
        directory = source.read()
        header_decoder = zlib.decompressobj()
        header = header_decoder.decompress(directory[1:])
        if not header.startswith(b'XFS '):
            raise ValueError('Unsupported XFS directory')
        consumed = len(directory) - 1 - len(header_decoder.unused_data)
        index_offset = directory.index(b'\x78\x9c', 1 + consumed)
        index = zlib.decompress(directory[index_offset:])
        name = b'setitemdata.dat'
        if index.count(name) != 1:
            raise ValueError('Expected exactly one setitemdata.dat')
        record = index.index(name) + len(name)
        offset = int.from_bytes(index[record:record + 6], 'little')
        size, packed_size = struct.unpack_from('<II', index, record + 6)
        source.seek(offset)
        packed = source.read(packed_size)
        table = zlib.decompress(packed[8:])
        if len(table) != size:
            raise ValueError('Incomplete setitemdata.dat')
        return table


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('archive', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    table = extract_set_table(args.archive)
    if len(table) % 140:
        raise ValueError('Unexpected 2012 set record size')
    bundles = {}
    for offset in range(0, len(table), 140):
        values = struct.unpack_from('<35I', table, offset)
        code = str(values[0])
        if code in bundles:
            raise ValueError('Duplicate bundle ID: ' + code)
        parts = [{'item_id': values[i], 'days': values[i + 1]}
                 for i in range(1, 35, 2) if values[i]]
        # 14901..14910 are empty reserved records, not deliverable sets.
        if parts:
            if any(p['days'] not in (1, 7, 30) for p in parts):
                raise ValueError('Unexpected component duration: ' + code)
            bundles[code] = parts
    document = {'source': '2012 Softnyx wolf.xfs:setitemdata.dat',
                'table_sha256': hashlib.sha256(table).hexdigest(),
                'record_size': 140, 'bundles': bundles}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(document, indent=2) + '\n', encoding='utf-8')
    print(f'Imported {len(bundles)} bundles from the installed client')


if __name__ == '__main__':
    main()
