"""Serve only the release archive and checksum to selected Radmin peers."""
import argparse
import html
import http.server
import ipaddress
import json
import os
from pathlib import Path
import re
from urllib.parse import quote, unquote, urlsplit


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--bind', required=True)
    parser.add_argument('--port', type=int, default=18081)
    parser.add_argument('--allow', action='append', required=True)
    parser.add_argument('--archive', type=Path, required=True)
    parser.add_argument('--ready', type=Path, required=True)
    parser.add_argument('--stop', type=Path, required=True)
    args = parser.parse_args()
    radmin = ipaddress.ip_network('26.0.0.0/8')
    if ipaddress.ip_address(args.bind) not in radmin:
        parser.error('Bind address must belong to the Radmin adapter')
    allowed = set(args.allow)
    allowed.add(args.bind)
    archive = args.archive.resolve(strict=True)
    files = {'/' + archive.name: archive}
    checksum = Path(str(archive) + '.sha256.txt')
    if checksum.is_file():
        files['/' + checksum.name] = checksum

    class Handler(http.server.BaseHTTPRequestHandler):
        def do_HEAD(self):
            self.serve(head=True)

        def do_GET(self):
            self.serve(head=False)

        def serve(self, head):
            if self.client_address[0] not in allowed:
                self.send_error(403)
                return
            path = unquote(urlsplit(self.path).path)
            if path == '/':
                links = ''.join('<li><a href="%s">%s</a></li>' %
                                (quote(name), html.escape(file.name))
                                for name, file in files.items())
                data = ('<!doctype html><meta charset="utf-8"><title>WolfTeam LAN</title>'
                        '<h1>WolfTeam 2012 LAN</h1><ul>' + links + '</ul>'
                        '<p>ZIP dosyasını çıkarıp WolfteamBaslat.bat dosyasını çalıştır.</p>').encode()
                self.send_response(200)
                self.send_header('Content-Type', 'text/html; charset=utf-8')
                self.send_header('Content-Length', str(len(data)))
                self.end_headers()
                if not head:
                    self.wfile.write(data)
                return
            file = files.get(path)
            if file is None:
                self.send_error(404)
                return
            size = file.stat().st_size
            start, end, partial = 0, size - 1, False
            value = self.headers.get('Range')
            if value:
                match = re.fullmatch(r'bytes=(\d*)-(\d*)', value.strip())
                try:
                    if not match or not any(match.groups()):
                        raise ValueError()
                    first, last = match.groups()
                    if first:
                        start = int(first)
                        end = min(int(last), size - 1) if last else size - 1
                    else:
                        length = int(last)
                        if length <= 0:
                            raise ValueError()
                        start = max(0, size - length)
                    if start >= size or start > end:
                        raise ValueError()
                    partial = True
                except ValueError:
                    self.send_response(416)
                    self.send_header('Content-Range', 'bytes */%d' % size)
                    self.send_header('Content-Length', '0')
                    self.end_headers()
                    return
            self.send_response(206 if partial else 200)
            self.send_header('Content-Type', 'application/zip' if file == archive else 'text/plain')
            self.send_header('Content-Disposition', 'attachment; filename="%s"' % file.name)
            self.send_header('Accept-Ranges', 'bytes')
            self.send_header('Content-Length', str(end - start + 1))
            if partial:
                self.send_header('Content-Range', 'bytes %d-%d/%d' % (start, end, size))
            self.end_headers()
            if head:
                return
            try:
                with file.open('rb') as source:
                    source.seek(start)
                    remaining = end - start + 1
                    while remaining:
                        chunk = source.read(min(1024 * 1024, remaining))
                        if not chunk:
                            break
                        self.wfile.write(chunk)
                        remaining -= len(chunk)
            except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
                pass

    server = http.server.ThreadingHTTPServer((args.bind, args.port), Handler)
    server.daemon_threads = True
    server.timeout = .5
    args.ready.write_text(json.dumps({'pid': os.getpid(), 'bind': args.bind,
        'port': args.port, 'allowed': sorted(allowed), 'files': list(files)}), encoding='utf-8')
    print('Ready: http://%s:%d/' % (args.bind, args.port), flush=True)
    try:
        while not args.stop.exists():
            server.handle_request()
    finally:
        server.server_close()


if __name__ == '__main__':
    main()
