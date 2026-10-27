"""Windows desktop tool for browsing 2012 client assets, previewing lore/graphics, and managing local player inventory."""
from __future__ import annotations

import io
import json
import os
from pathlib import Path
import sqlite3
import struct
import subprocess
import sys
import time
import urllib.error
import urllib.request
import zlib
import tkinter as tk
from tkinter import messagebox, ttk

from PIL import Image, ImageDraw, ImageFilter


ROOT = Path(__file__).resolve().parent
API = 'http://127.0.0.1:18080/wolfteam/users.php'
CATALOG_PATH = ROOT / 'logan' / 'web' / 'wolfteam' / 'asset_catalog.json'
NO_WINDOW = getattr(subprocess, 'CREATE_NO_WINDOW', 0)

TURKISH_SEARCH_TRANS = str.maketrans({
    'ı': 'i', 'İ': 'i', 'I': 'i',
    'ğ': 'g', 'Ğ': 'g',
    'ü': 'u', 'Ü': 'u',
    'ş': 's', 'Ş': 's',
    'ö': 'o', 'Ö': 'o',
    'ç': 'c', 'Ç': 'c',
})


def normalize_search(text: str) -> str:
    """Normalize Turkish characters and case for responsive and forgiving search."""
    return text.casefold().translate(TURKISH_SEARCH_TRANS)


def sync_sqlite_removal(username: str, item_ids: list[int]):
    """Sync item deletion with LAN server SQLite store if present."""
    db_path = ROOT / 'server' / 'player_state.sqlite3'
    if not db_path.exists() or not username or not item_ids:
        return
    try:
        key = username.strip().casefold()
        with sqlite3.connect(str(db_path), timeout=3) as db:
            in_clause = ','.join('?' for _ in item_ids)
            db.execute(f"DELETE FROM items WHERE player=? AND item_id IN ({in_clause})", [key] + item_ids)
    except Exception:
        pass


def sync_sqlite_clear(username: str):
    """Sync full inventory clearing with LAN server SQLite store if present."""
    db_path = ROOT / 'server' / 'player_state.sqlite3'
    if not db_path.exists() or not username:
        return
    try:
        key = username.strip().casefold()
        with sqlite3.connect(str(db_path), timeout=3) as db:
            db.execute("DELETE FROM items WHERE player=?", (key,))
    except Exception:
        pass


class PreviewManager:
    """Extracts authentic 2012 in-game textures and generates sleek gaming pedestal preview cards."""

    def __init__(self, root_dir: Path):
        self.root_dir = root_dir
        self.preview_dir = root_dir / 'logan' / 'web' / 'wolfteam' / 'previews'
        self.preview_dir.mkdir(parents=True, exist_ok=True)
        self.cache: dict[str, tk.PhotoImage] = {}
        self.index_data = b''

        self.tattoo_map = {}
        map_path = root_dir / 'logan' / 'web' / 'wolfteam' / 'tattoo_textures.json'
        if map_path.exists():
            try:
                self.tattoo_map = json.loads(map_path.read_text(encoding='utf-8'))
            except Exception:
                pass

        archive_path = root_dir / 'wolf.xfs'
        if archive_path.exists():
            try:
                with archive_path.open('rb') as source:
                    directory_offset, = struct.unpack('<I', source.read(4))
                    source.seek(directory_offset)
                    directory = source.read()
                    dec = zlib.decompressobj()
                    dec.decompress(directory[1:])
                    c = len(directory) - 1 - len(dec.unused_data)
                    idx_off = directory.index(b'\x78\x9c', 1 + c)
                    self.index_data = zlib.decompress(directory[idx_off:])
            except Exception:
                self.index_data = b''

    def extract_file(self, filename: str) -> bytes | None:
        if not self.index_data or not filename:
            return None
        archive_path = self.root_dir / 'wolf.xfs'
        if not archive_path.exists():
            return None

        wanted = filename.lower().encode('latin1')
        pos = self.index_data.lower().find(wanted)
        if pos < 0:
            return None
        record = pos + len(wanted)
        offset = int.from_bytes(self.index_data[record:record + 6], 'little')
        size, packed_size = struct.unpack_from('<II', self.index_data, record + 6)

        try:
            with archive_path.open('rb') as source:
                source.seek(offset)
                raw_packed = source.read(packed_size)

            content_parts = []
            remaining = raw_packed
            total_size = 0
            while total_size < size and remaining:
                stream_offset = next((i for i in range(min(16, len(remaining) - 1))
                                      if remaining[i] & 0x0F == 8 and ((remaining[i] << 8) | remaining[i + 1]) % 31 == 0), None)
                if stream_offset is None:
                    break
                dec = zlib.decompressobj()
                block = dec.decompress(remaining[stream_offset:])
                content_parts.append(block)
                content_parts.append(dec.flush())
                total_size += len(block)
                remaining = dec.unused_data
            return b''.join(content_parts)[:size]
        except Exception:
            return None

    def decode_dtx(self, dtx_bytes: bytes) -> Image.Image | None:
        if not dtx_bytes or len(dtx_bytes) < 164:
            return None
        w, h = struct.unpack_from('<HH', dtx_bytes, 8)
        fmt = struct.unpack_from('<I', dtx_bytes, 16)[0]

        # Raw uncompressed 32-bit BGRA (official LithTech format in wolf.xfs)
        if len(dtx_bytes) >= 164 + w * h * 4:
            try:
                return Image.frombytes('RGBA', (w, h), dtx_bytes[164:164 + w * h * 4], 'raw', 'BGRA')
            except Exception:
                pass

        # DXT compressed fallback
        fourcc = b'DXT1' if fmt == 8 else (b'DXT3' if fmt == 9 else b'DXT5')
        linear_size = (w * h // 2) if fourcc == b'DXT1' else (w * h)
        header = bytearray(128)
        struct.pack_into('<4s', header, 0, b'DDS ')
        struct.pack_into('<I', header, 4, 124)
        struct.pack_into('<I', header, 8, 0x1 | 0x2 | 0x4 | 0x1000 | 0x80000)
        struct.pack_into('<I', header, 12, h)
        struct.pack_into('<I', header, 16, w)
        struct.pack_into('<I', header, 20, linear_size)
        struct.pack_into('<I', header, 28, 1)
        struct.pack_into('<I', header, 76, 32)
        struct.pack_into('<I', header, 80, 0x4)
        struct.pack_into('<4s', header, 84, fourcc)
        struct.pack_into('<I', header, 108, 0x1000)
        mip0 = dtx_bytes[164:164 + linear_size]
        try:
            return Image.open(io.BytesIO(bytes(header) + mip0)).convert('RGBA')
        except Exception:
            return None

    def _resolve_asset_texture(self, item_id: int, name: str, category: str, type_name: str, is_bundle: bool):
        """Map any catalog item to its official in-game texture, flags, and crop box."""
        n = name.lower()
        cat = category.lower()
        t = type_name.lower()
        is_gold = any(k in n for k in ['altın', 'gold', 'gl', 'özel', 'antique'])

        # 1. Tattoos
        if item_id in self.tattoo_map or str(item_id) in self.tattoo_map:
            tex_name = self.tattoo_map.get(item_id) or self.tattoo_map.get(str(item_id))
            return tex_name, is_gold, False, None, '🐉'

        # 2. Bundles & Sets
        if is_bundle or 'paket' in cat:
            if 'ejder' in n and 'altın' in n:
                return 'tattoo_dragon_gl_set.dtx', True, False, (0, 0, 206, 70), '✦'
            if 'aslan' in n and 'altın' in n:
                return 'tattoo_lion_gl_set.dtx', True, False, (0, 0, 206, 70), '✦'
            if 'aslan' in n:
                return 'tattoo_lion_set.dtx', False, False, (0, 0, 206, 70), '✦'
            if 'anka' in n or 'phoenix' in n:
                return 'tattoo_phoenix_gl_set.dtx', True, False, (0, 0, 206, 70), '✦'
            if 'pegasus' in n:
                return 'tattoo_pegasus_gl_set.dtx', True, False, (0, 0, 206, 70), '✦'
            if 'tarantula' in n:
                return 'tattoo_tarantula_gl_set.dtx', True, False, (0, 0, 206, 70), '✦'
            if 'boğa' in n or 'taurus' in n:
                return 'tattoo_taurus_gl_set.dtx', True, False, (0, 0, 206, 70), '✦'
            if 'christopher' in n:
                return 'christopher_set.dtx', True, False, (21, 0, 183, 69), '👤'
            if 'pedro' in n:
                return 'pedro_set.dtx', True, False, (21, 0, 183, 69), '👤'
            if 'angela' in n:
                return 'angela_set.dtx', True, False, (21, 0, 183, 69), '👤'
            if 'adriana' in n:
                return 'adriana_set.dtx', True, False, (21, 0, 183, 69), '👤'
            if 'operasyon' in n or 'ops' in n:
                return 'ops_set.dtx', True, False, None, '✦'
            if 'kamuflaj' in n:
                return 'camouflage_set.dtx', False, False, None, '✦'
            if 'çöl' in n or 'desert' in n:
                return 'desert_set.dtx', False, False, None, '✦'
            if 'gümüş' in n or 'silver' in n:
                return 'silver_set.dtx', False, False, None, '✦'
            if 'klasik' in n:
                return 'classic_set.dtx', False, False, None, '✦'
            return 'vl_item_set.dtx', is_gold, False, None, '✦'

        # 3. Characters
        if 'karakter' in cat or (30001 <= item_id <= 30050):
            if 'özel versiyon' in n or 'özel' in n or 'se' in n:
                if 'christopher' in n:
                    return 'pl_k_wolf_christopher_se.dtx', True, True, None, '🐺'
                if 'pedro' in n:
                    return 'pl_k_wolf_pedro_se.dtx', True, True, None, '🐺'
                if 'angela' in n:
                    return 'pl_k_wolf_angela_se.dtx', True, True, None, '🐺'
                if 'adriana' in n:
                    return 'pl_k_wolf_adriana_se.dtx', True, True, None, '🐺'
                return 'pl_k_wolf_berserker.dtx', True, True, None, '🐺'
            if 'christopher' in n:
                return 'christopher_set.dtx', False, False, (21, 0, 183, 69), '👤'
            if 'pedro' in n:
                return 'pedro_set.dtx', False, False, (21, 0, 183, 69), '👤'
            if 'angela' in n:
                return 'angela_set.dtx', False, False, (21, 0, 183, 69), '👤'
            if 'adriana' in n:
                return 'adriana_set.dtx', False, False, (21, 0, 183, 69), '👤'
            if 'yiğit' in n or 'yigit' in n:
                return 'pl_k_wolf_basic.dtx', True, True, None, '🐺'
            if 'geremi' in n:
                return 'pl_k_wolf_smart.dtx', False, True, None, '🐺'
            if 'marien' in n:
                return 'pl_k_wolf_guardian.dtx', False, True, None, '🐺'
            if 'lin' in n:
                return 'pl_k_wolf_psycho.dtx', False, True, None, '🐺'
            if 'reinhard' in n:
                return 'pl_k_wolf_berserker.dtx', False, True, None, '🐺'
            return 'pl_k_wolf_basic.dtx', is_gold, True, None, '🐺'

        # 4. Claws (Pençeler)
        if 'pençe' in n or 'claw' in n:
            if 'altın' in n or 'gold' in n:
                return 'wolf_claw_gold.dtx', True, False, None, '🐾'
            if 'gümüş' in n or 'silver' in n:
                return 'wolf_claw_silver.dtx', False, False, None, '🐾'
            return 'wolf_claw_black.dtx', False, False, None, '🐾'

        # 5. Wolf Skills
        if 'yetenek' in n or 'kurt yeteneği' in n or 'skill' in n:
            if 'buz' in n or 'ice' in n:
                return 'skillup_icewolf.dtx', False, False, None, '🐺'
            if 'vahşi' in n or 'berserker' in n:
                return 'skillup_berserkerwolf.dtx', False, False, None, '🐺'
            if 'hayalet' in n or 'ghost' in n:
                return 'skillup_ghostwolf.dtx', False, False, None, '🐺'
            if 'gardiyan' in n or 'guardian' in n:
                return 'skillup_guardianwolf.dtx', False, False, None, '🐺'
            if 'güç' in n or 'power' in n:
                return 'skillup_powerwolf.dtx', False, False, None, '🐺'
            if 'zeki' in n or 'smart' in n:
                return 'skillup_smartwolf.dtx', False, False, None, '🐺'
            if 'deli' in n or 'psycho' in n:
                return 'skillup_psychowolf.dtx', False, False, None, '🐺'
            return 'skillup_guardianwolf.dtx', False, False, None, '🐺'

        # 6. Boxes & Consumables
        if any(k in n for k in ['şans kutusu', 'sandık', 'lucky box', 'kutu']):
            if 'kurt' in n:
                return 'wolfbox_2.dtx', is_gold, False, None, '🎁'
            return 'lb_lucky_box.dtx', is_gold, False, None, '🎁'
        if 'güçlü kullanıcı' in n or 'power user' in n:
            return 'lb_power_user_package.dtx', True, False, None, '⭐'
        if 'hızlı gp' in n or 'hızlı altın' in n or 'steampack' in n:
            return 'pl_item_steampack.dtx', False, False, None, '⚡'

        # 7. Weapons
        if 'silah' in cat or any(k in t for k in ['tabanca', 'tüfek', 'sniper', 'bıçak', 'bomba', 'ağır']):
            aliases = [
                (('em-92', '92fs', '92', 'm9'), 'pl_k_em-92fs.dtx'),
                (('gloc', 'g-17', 'glock'), 'pl_k_gloc-17.dtx'),
                (('bıçak', 'tfk', 'tk', 'balta', 'knife'), 'pl_k_rmk4.dtx'),
                (('el bombası', 'bomba', 'grenade', 'rgd', 'rgs', 'mk-5', 'mk-6'), 'pl_k_c-nadea.dtx'),
                (('mühimmat', 'ammo'), 'pl_k_em-92fs.dtx'),
                (('em-1911', '1911'), 'pl_k_em-92fs.dtx'),
                (('ruger', 'karakal'), 'pl_k_gloc-17.dtx'),
                (('usf',), 'pl_k_usf.dtx'),
                (('em-16', 'em-4', 'spw'), 'pl_k_em-4c_gl.dtx' if is_gold else 'pl_k_em4-a1.dtx'),
                (('akei-47', 'ak-47', 'akeis'), 'pl_k_akeis-47.dtx' if is_gold else 'pl_k_akei-47.dtx'),
                (('auz', 'steyr'), 'pl_k_auz_a3_c_gl.dtx' if is_gold else 'pl_k_auz_a3_mc.dtx'),
                (('criss', 'vector'), 'pl_k_criss.dtx'),
                (('de-50', 'desert eagle', 'de gl', 'de-'), 'pl_k_de.dtx'),
                (('sz-550', 'sz-552', 'sig'), 'pl_k_sz-550.dtx'),
                (('i-115', 'sniper', 'awp'), 'pl_k_i-115a1.dtx'),
                (('zaitsev', 'zait'), 'pl_k_zaitsevrifle.dtx'),
                (('fanas', 'famas'), 'pl_k_fanas.dtx'),
                (('robinson', 'xcr'), 'pl_k_robinson_xcr_gl.dtx' if is_gold else 'pl_k_robinson.dtx'),
                (('scal', 'scar'), 'pl_k_scal-sf.dtx'),
                (('xem-8', 'xm8'), 'pl_k_xem-8.dtx'),
                (('z-36', 'g36'), 'pl_k_z-36.dtx'),
                (('mp7', 'hkei-mp7'), 'pl_k_hkei-mp7a1.dtx'),
                (('emp-5', 'mp5'), 'pl_k_emp-5_cs_gold.dtx' if is_gold else 'pl_k_emp-5_cs_mc.dtx'),
                (('emg-42', 'mg42'), 'pl_k_emg-42.dtx'),
                (('minimi', 'em-249'), 'pl_k_em-249_minimi.dtx'),
                (('aaa-12', 'aa-12'), 'pl_k_aaa-12.dtx'),
                (('supas', 'spas'), 'pl_k_supas-pt.dtx'),
                (('m-1014', 'benelli'), 'pl_k_m-1014.dtx'),
                (('tommy', 'thompson'), 'pl_k_tommy-45.dtx'),
                (('svdr', 'dragunov'), 'pl_k_svdr_gold.dtx' if is_gold else 'pl_k_svdr_classic.dtx'),
                (('etsr-1',), 'pl_k_etsr-1.dtx'),
                (('msd-90',), 'pl_k_msd-90.dtx'),
                (('c-95',), 'pl_k_c-95.dtx'),
                (('cxe4',), 'pl_k_cxe4-storm.dtx'),
                (('ef-2000',), 'pl_k_ef-2000.dtx'),
                (('elwrci',), 'pl_k_elwrci_m6a2.dtx'),
                (('exk-8',), 'pl_k_exk-8.dtx'),
                (('hke417', 'hk417'), 'pl_k_hke417.dtx'),
                (('kar_98', 'kar98'), 'pl_k_kar_98k.dtx'),
                (('wt-mg1',), 'pl_k_wt-mg1.dtx'),
                (('wt-rl2',), 'pl_k_wt-rl2.dtx'),
            ]
            for keys, target in aliases:
                if any(k in n for k in keys):
                    return target, is_gold, True, None, '⚔'
            return 'pl_k_em-92fs.dtx', is_gold, True, None, '⚔'

        # 8. Character Gear (Kıyafet / Başlık / Yelek)
        if 'christopher' in n:
            return 'christopher_set.dtx', False, False, (21, 0, 183, 69), '🛡'
        if 'pedro' in n:
            return 'pedro_set.dtx', False, False, (21, 0, 183, 69), '🛡'
        if 'angela' in n:
            return 'angela_set.dtx', False, False, (21, 0, 183, 69), '🛡'
        if 'adriana' in n:
            return 'adriana_set.dtx', False, False, (21, 0, 183, 69), '🛡'

        return 'vl_item_set.dtx', is_gold, False, None, '✦'

    def get_preview_image(self, item_id: int, name: str, category: str, type_name: str = '', is_bundle: bool = False) -> tk.PhotoImage | None:
        key = f"{'bundle_' if is_bundle else 'item_'}{item_id}"
        if key in self.cache:
            return self.cache[key]

        out_path = self.preview_dir / f"{key}.png"
        if not out_path.exists():
            tex_name, is_gold, is_silhouette, crop_rect, fallback_sym = self._resolve_asset_texture(
                item_id, name, category, type_name, is_bundle
            )

            w, h = 300, 140
            card = Image.new('RGBA', (w, h), (13, 18, 27, 255))
            draw = ImageDraw.Draw(card)

            accent = (225, 175, 45) if is_gold else (58, 142, 230)

            # Smooth vertical gradient
            for y in range(h):
                ratio = y / h
                r = int(12 + (24 - 12) * ratio)
                g = int(17 + (32 - 17) * ratio)
                b = int(25 + (45 - 25) * ratio)
                draw.line([(0, y), (w, y)], fill=(r, g, b, 255))

            # Soft ambient spotlight
            glow_img = Image.new('RGBA', (w, h), (0, 0, 0, 0))
            glow_draw = ImageDraw.Draw(glow_img)
            cx, cy = w // 2, h // 2 + 5
            for i in range(12, 0, -1):
                rx = int(100 * (i / 12))
                ry = int(55 * (i / 12))
                alpha = int(40 * (1.0 - (i / 12) ** 1.5))
                glow_draw.ellipse([(cx - rx, cy - ry), (cx + rx, cy + ry)], fill=(accent[0], accent[1], accent[2], alpha))
            glow_blurred = glow_img.filter(ImageFilter.GaussianBlur(8))
            card.alpha_composite(glow_blurred)

            # Pedestal baseline
            draw.ellipse([(w//2 - 65, h - 20), (w//2 + 65, h - 9)], outline=(accent[0]//4, accent[1]//4, accent[2]//4, 180), width=1)

            # Extract texture
            tex = None
            if tex_name:
                raw = self.extract_file(tex_name)
                if raw:
                    tex = self.decode_dtx(raw)

            if tex:
                if crop_rect:
                    tex = tex.crop(crop_rect)
                else:
                    bbox = tex.getbbox()
                    if bbox:
                        tex = tex.crop(bbox)

                tw, th = tex.size

                if is_silhouette:
                    max_w, max_h = 220, 90
                    scale = min(max_w / tw, max_h / th)
                    target_w = max(1, int(tw * scale))
                    target_h = max(1, int(th * scale))
                    scaled = tex.resize((target_w, target_h), Image.Resampling.LANCZOS)

                    r_ch, g_ch, b_ch, a_ch = scaled.split()
                    if is_gold:
                        tint = Image.new('RGB', (target_w, target_h), (255, 218, 70))
                    else:
                        tint = Image.new('RGB', (target_w, target_h), (220, 235, 255))
                    tinted = Image.merge('RGBA', (tint.split()[0], tint.split()[1], tint.split()[2], a_ch))

                    # Drop shadow
                    shadow = Image.new('RGBA', (target_w + 10, target_h + 10), (0, 0, 0, 0))
                    shadow_mask = a_ch.point(lambda p: int(p * 0.45))
                    black = Image.new('RGBA', (target_w, target_h), (5, 8, 14, 255))
                    shadow.paste(black, (5, 5), shadow_mask)
                    shadow = shadow.filter(ImageFilter.GaussianBlur(3))

                    paste_x = (w - target_w) // 2
                    paste_y = (h - target_h) // 2 - 2
                    card.paste(shadow, (paste_x - 3, paste_y - 2), shadow)
                    card.paste(tinted, (paste_x, paste_y), tinted)
                else:
                    max_w, max_h = 240, 110
                    scale = min(max_w / tw, max_h / th)
                    target_w = max(1, int(tw * scale))
                    target_h = max(1, int(th * scale))
                    scaled = tex.resize((target_w, target_h), Image.Resampling.LANCZOS)

                    paste_x = (w - target_w) // 2
                    paste_y = (h - target_h) // 2
                    card.paste(scaled, (paste_x, paste_y), scaled)
            else:
                draw.rounded_rectangle([(w//2 - 40, h//2 - 35), (w//2 + 40, h//2 + 25)], radius=8, fill=(20, 28, 42), outline=(42, 58, 80))
                draw.text((w//2 - 12, h//2 - 16), fallback_sym, fill=accent, font_size=20)

            # Outer border & accent line
            draw.rounded_rectangle([(1, 1), (w - 2, h - 2)], radius=6, outline=(38, 50, 68), width=1)
            draw.rounded_rectangle([(1, 1), (w - 2, 3)], radius=1, fill=accent)

            card.save(out_path, format='PNG')

        try:
            photo = tk.PhotoImage(file=str(out_path))
            self.cache[key] = photo
            return photo
        except Exception:
            return None


class InventoryManager(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title('WolfTeam 2012 · Yerel Envanter Yöneticisi')
        self.geometry('1280x760')
        self.minsize(1060, 640)
        self.configure(bg='#10151f')

        self.preview_mgr = PreviewManager(ROOT)
        self.catalog = json.loads(CATALOG_PATH.read_text(encoding='utf-8'))
        self.asset_rows = self.catalog.get('items', [])
        self.bundle_rows = self.catalog.get('bundles', [])

        self.item_lookup: dict[int, dict] = {int(r['id']): r for r in self.asset_rows}
        self.bundle_lookup: dict[int, dict] = {int(b['id']): b for b in self.bundle_rows}
        self.item_names: dict[int, str] = {int(r['id']): r.get('name') or f"Eşya {r['id']}" for r in self.asset_rows}

        self.account_rows = []
        self.display_rows = []
        self.owned: dict[int, bool] = {}
        self.current_preview_item: dict | None = None
        self.current_preview_is_bundle: bool = False
        self.host_process = None
        self.host_run = None

        self._style()
        self._build()
        self.protocol('WM_DELETE_WINDOW', self.close)
        self.after(100, self.connect)

    def _style(self):
        style = ttk.Style(self)
        style.theme_use('clam')
        style.configure('TFrame', background='#10151f')
        style.configure('TLabel', background='#10151f', foreground='#e2e8f0', font=('Segoe UI', 10))
        style.configure('Title.TLabel', font=('Segoe UI Semibold', 16), foreground='#ffffff')
        style.configure('Section.TLabel', font=('Segoe UI Semibold', 11), foreground='#f8fafc')
        style.configure('Hint.TLabel', foreground='#94a3b8', font=('Segoe UI', 9))
        style.configure('TButton', font=('Segoe UI Semibold', 9), padding=(10, 6))
        style.configure('Accent.TButton', background='#d2a63a', foreground='#10151f')
        style.map('Accent.TButton', background=[('active', '#ecc055')])
        style.configure('Danger.TButton', background='#dc2626', foreground='#ffffff')
        style.map('Danger.TButton', background=[('active', '#ef4444')])
        style.configure('TCombobox', padding=5)
        style.configure('Treeview', background='#18202c', fieldbackground='#18202c', foreground='#e2e8f0', rowheight=26)
        style.configure('Treeview.Heading', background='#253245', foreground='#ffffff', font=('Segoe UI Semibold', 9))

    def _build(self):
        shell = ttk.Frame(self, padding=16)
        shell.pack(fill='both', expand=True)

        # Top Bar
        top_bar = ttk.Frame(shell)
        top_bar.pack(fill='x', pady=(0, 12))

        title_box = ttk.Frame(top_bar)
        title_box.pack(side='left')
        ttk.Label(title_box, text='WolfTeam 2012 · Envanter ve Asset Yöneticisi', style='Title.TLabel').pack(anchor='w')
        ttk.Label(title_box, text='Oyun içi varlık kataloğunu incele, detayları ve açıklamaları gör, hesabına ekle veya envanterden sil.',
                  style='Hint.TLabel').pack(anchor='w')

        acc_box = ttk.Frame(top_bar)
        acc_box.pack(side='right', fill='y')
        ttk.Label(acc_box, text='Oyuncu Hesabı:').pack(side='left', padx=(0, 6))
        self.account = ttk.Combobox(acc_box, state='readonly', width=22)
        self.account.pack(side='left', padx=(0, 10))
        self.account.bind('<<ComboboxSelected>>', lambda _e: self.refresh_inventory())
        ttk.Button(acc_box, text='🔄 Yenile', command=self.refresh_all).pack(side='left')

        # Main 3-Pane Area
        panes = ttk.Panedwindow(shell, orient='horizontal')
        panes.pack(fill='both', expand=True)

        left = ttk.Frame(panes, padding=(0, 0, 8, 0))
        center = ttk.Frame(panes, padding=(8, 0, 8, 0))
        right = ttk.Frame(panes, padding=(8, 0, 0, 0))

        panes.add(left, weight=3)
        panes.add(center, weight=3)
        panes.add(right, weight=3)

        # ==========================================
        # LEFT PANE: Asset Catalog Browser
        # ==========================================
        ttk.Label(left, text='2012 Oyun Kataloğu', style='Section.TLabel').pack(anchor='w', pady=(0, 4))

        cat_bar = ttk.Frame(left)
        cat_bar.pack(fill='x', pady=(0, 6))
        categories = ['Tümü', 'Silah / ekipman', 'Karakter', 'Güç eşyası', 'Kıyafet / karakter eşyası', 'Paket', 'Diğer eşya']
        self.category = ttk.Combobox(cat_bar, state='readonly', values=categories, width=16)
        self.category.set('Tümü')
        self.category.pack(side='left', fill='x', expand=True, padx=(0, 6))
        self.category.bind('<<ComboboxSelected>>', lambda _e: self.refresh_catalog())

        self.search = ttk.Entry(cat_bar, width=16)
        self.search.pack(side='right', fill='x', expand=True)
        self.search.bind('<KeyRelease>', lambda _e: self.refresh_catalog())

        list_frame = ttk.Frame(left)
        list_frame.pack(fill='both', expand=True)
        self.asset_list = tk.Listbox(
            list_frame, selectmode='extended', exportselection=False,
            bg='#18202c', fg='#e2e8f0', selectbackground='#b88b22',
            selectforeground='#ffffff', activestyle='none', relief='flat',
            font=('Segoe UI', 9)
        )
        scroll_left = ttk.Scrollbar(list_frame, orient='vertical', command=self.asset_list.yview)
        self.asset_list.configure(yscrollcommand=scroll_left.set)
        self.asset_list.pack(side='left', fill='both', expand=True)
        scroll_left.pack(side='right', fill='y')
        self.asset_list.bind('<<ListboxSelect>>', self.on_catalog_selected)

        ttk.Button(left, text='➕ Seçilenleri Envantere Ekle', style='Accent.TButton',
                   command=self.add_selected).pack(fill='x', pady=(8, 0))

        # ==========================================
        # CENTER PANE: Item Preview & Description Box
        # ==========================================
        ttk.Label(center, text='Oyun İçi Önizleme & Detay', style='Section.TLabel').pack(anchor='w', pady=(0, 6))

        preview_card = ttk.Frame(center)
        preview_card.pack(fill='both', expand=True)

        self.preview_canvas = tk.Label(preview_card, bg='#0d121b', relief='flat')
        self.preview_canvas.pack(fill='x', pady=(0, 8))

        self.detail_name = ttk.Label(preview_card, text='Bir eşya seçin', font=('Segoe UI Semibold', 12), foreground='#ffffff')
        self.detail_name.pack(anchor='w')

        self.detail_badges = ttk.Label(preview_card, text='Detaylar burada gösterilir', font=('Segoe UI', 9), foreground='#d2a63a')
        self.detail_badges.pack(anchor='w', pady=(2, 6))

        self.detail_stats = ttk.Label(preview_card, text='', font=('Segoe UI', 9), foreground='#94a3b8')
        self.detail_stats.pack(anchor='w', pady=(0, 6))

        ttk.Label(preview_card, text='Oyun İçi Açıklama & Özellikler:', font=('Segoe UI Semibold', 9), foreground='#cbd5e1').pack(anchor='w', pady=(4, 2))

        desc_frame = ttk.Frame(preview_card)
        desc_frame.pack(fill='both', expand=True)
        self.desc_text = tk.Text(
            desc_frame, wrap='word', bg='#121822', fg='#e2e8f0',
            relief='flat', font=('Segoe UI', 9), padx=10, pady=8
        )
        desc_scroll = ttk.Scrollbar(desc_frame, orient='vertical', command=self.desc_text.yview)
        self.desc_text.configure(yscrollcommand=desc_scroll.set)
        self.desc_text.pack(side='left', fill='both', expand=True)
        desc_scroll.pack(side='right', fill='y')

        # Center quick action
        self.btn_center_action = ttk.Button(center, text='➕ Bu Eşyayı Hesaba Ekle', style='Accent.TButton',
                                            command=self.add_current_preview_item)
        self.btn_center_action.pack(fill='x', pady=(8, 0))

        # ==========================================
        # RIGHT PANE: Owned Inventory & Removal Controls
        # ==========================================
        right_header = ttk.Frame(right)
        right_header.pack(fill='x', pady=(0, 6))
        ttk.Label(right_header, text='Hesaptaki Envanter', style='Section.TLabel').pack(side='left')
        self.inv_count_label = ttk.Label(right_header, text='0 Eşya', style='Hint.TLabel')
        self.inv_count_label.pack(side='right')

        inv_frame = ttk.Frame(right)
        inv_frame.pack(fill='both', expand=True)
        columns = ('id', 'name', 'category', 'expiry', 'count')
        self.inventory = ttk.Treeview(inv_frame, columns=columns, show='headings', selectmode='extended')
        for key, title, width in [
            ('id', 'ID', 55),
            ('name', 'Eşya Adı', 170),
            ('category', 'Tür', 95),
            ('expiry', 'Süre', 85),
            ('count', 'Adet', 45)
        ]:
            self.inventory.heading(key, text=title)
            self.inventory.column(key, width=width, anchor='w')

        inv_scroll = ttk.Scrollbar(inv_frame, orient='vertical', command=self.inventory.yview)
        self.inventory.configure(yscrollcommand=inv_scroll.set)
        self.inventory.pack(side='left', fill='both', expand=True)
        inv_scroll.pack(side='right', fill='y')
        self.inventory.bind('<<TreeviewSelect>>', self.on_inventory_selected)
        self.inventory.bind('<Delete>', lambda _e: self.remove_selected_inventory())

        # Inventory context menu
        self.inv_menu = tk.Menu(self, tearoff=0, bg='#1e2638', fg='#ffffff', activebackground='#b88b22', activeforeground='#ffffff')
        self.inv_menu.add_command(label='🗑️ Seçileni Envanterden Sil', command=self.remove_selected_inventory)
        self.inv_menu.add_command(label='📋 Eşya ID Kopyala', command=self.copy_selected_id)
        self.inv_menu.add_separator()
        self.inv_menu.add_command(label='🔄 Yenile', command=self.refresh_inventory)
        self.inventory.bind('<Button-3>', self.show_inv_context_menu)

        # Inventory action buttons
        inv_btn_bar = ttk.Frame(right)
        inv_btn_bar.pack(fill='x', pady=(8, 0))
        ttk.Button(inv_btn_bar, text='🗑️ Seçileni Envanterden Sil', style='Danger.TButton',
                   command=self.remove_selected_inventory).pack(side='left', fill='x', expand=True, padx=(0, 4))
        ttk.Button(inv_btn_bar, text='🧹 Envanteri Temizle',
                   command=self.clear_all_inventory).pack(side='right', padx=(4, 0))

        # Bottom Status Bar
        self.status = ttk.Label(shell, text='Yerel veritabanına bağlanıyor…', style='Hint.TLabel')
        self.status.pack(anchor='w', pady=(10, 0))

    def request(self, req, params=None, body=None, timeout=3):
        from urllib.parse import urlencode
        url = API + '?' + urlencode({'req': req, **(params or {})})
        data = json.dumps(body).encode('utf-8') if body is not None else None
        request = urllib.request.Request(url, data=data, headers={'Content-Type': 'application/json'},
                                         method='POST' if data is not None else 'GET')
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return json.loads(response.read().decode('utf-8'))

    def connect(self):
        try:
            users = self.request('adminusers')
        except Exception:
            if self.host_process is None:
                self.start_local_api()
            self.after(350, self.connect)
            return
        self.account_rows = users
        self.account['values'] = [f"{row['username']}  (#{row['id']})" for row in users]
        if users:
            self.account.current(0)
            self.refresh_inventory()
            self.status.configure(text='Hazır · Eşya ekleme ve silme işlemleri yerel sunucuya anında kaydedilir.')
        else:
            self.status.configure(text='Henüz hesap yok. Oyunu bir kez başlatıp yerel hesap oluşturun.')
        self.refresh_catalog()

    def start_local_api(self):
        run = ROOT / '.runtime' / f"inventory-manager-{os.getpid()}"
        run.mkdir(parents=True, exist_ok=True)
        for name in ('ready.json', 'stop.request'):
            path = run / name
            if path.exists():
                path.unlink()
        out = open(run / 'host.stdout.log', 'ab')
        err = open(run / 'host.stderr.log', 'ab')
        self.host_process = subprocess.Popen(
            [sys.executable, str(ROOT / 'logan' / 'host.py'), '--services-only', '--run', str(run)],
            cwd=ROOT, stdin=subprocess.DEVNULL, stdout=out, stderr=err,
            creationflags=NO_WINDOW,
        )
        self.host_run = run
        self._host_logs = (out, err)
        self.status.configure(text='Yerel veritabanı arka planda başlatılıyor…')

    def selected_user_id(self) -> int:
        index = self.account.current()
        return int(self.account_rows[index]['id']) if 0 <= index < len(self.account_rows) else 0

    def selected_username(self) -> str:
        index = self.account.current()
        return self.account_rows[index]['username'] if 0 <= index < len(self.account_rows) else ''

    def refresh_catalog(self):
        query = normalize_search(self.search.get().strip())
        category = self.category.get()
        rows = []
        for item in self.asset_rows:
            if category not in ('Tümü', item.get('category', '')):
                continue
            if query:
                searchable = normalize_search(
                    f"{item['id']} {item.get('name', '')} {item.get('category', '')} {item.get('type_name', '')} {' '.join(item.get('sources', []))}"
                )
                if query not in searchable:
                    continue
            rows.append(('item', item))
        for bundle in self.bundle_rows:
            if category not in ('Tümü', bundle.get('category', '')):
                continue
            if query:
                sub_names = ' '.join(self.item_names.get(p['item_id'], '') for p in bundle.get('parts', []))
                searchable = normalize_search(
                    f"{bundle['id']} {bundle.get('name', '')} paket set {' '.join(str(p['item_id']) for p in bundle.get('parts', []))} {sub_names}"
                )
                if query not in searchable:
                    continue
            rows.append(('bundle', bundle))
        rows.sort(key=lambda entry: (entry[1].get('category', ''), entry[1]['id']))
        self.display_rows = rows
        self.asset_list.delete(0, 'end')
        for kind, row in rows:
            label = row.get('label') or f"{row['id']} · {row.get('name') or 'asset'}"
            self.asset_list.insert('end', f"[{row.get('category', 'Eşya')}]  {label}" + ('  · set' if kind == 'bundle' else ''))

    def display_item_details(self, row: dict, is_bundle: bool = False):
        """Render in-game preview graphic and full official Turkish lore/description."""
        self.current_preview_item = row
        self.current_preview_is_bundle = is_bundle

        item_id = int(row['id'])
        name = row.get('name') or ('Paket ' + str(item_id) if is_bundle else f"Eşya {item_id}")
        category = row.get('category', 'Paket' if is_bundle else 'Eşya')
        type_name = row.get('type_name', '')

        # Preview image
        photo = self.preview_mgr.get_preview_image(item_id, name, category, type_name, is_bundle)
        if photo:
            self.preview_canvas.configure(image=photo, text='')
            self.preview_canvas.image = photo
        else:
            self.preview_canvas.configure(image='', text=f"[ {category} ]\n{name}")

        self.detail_name.configure(text=name)
        type_badge = f" · {type_name}" if type_name else ''
        self.detail_badges.configure(text=f"[{category.upper()}]{type_badge}  ·  Eşya ID: #{item_id}")

        stats_parts = []
        if row.get('weight'):
            stats_parts.append(f"Ağırlık: {row['weight']}")
        if row.get('ammo'):
            stats_parts.append(f"Mühimmat: {row['ammo']}")
        self.detail_stats.configure(text='  |  '.join(stats_parts))

        # Description text
        self.desc_text.delete('1.0', 'end')
        desc = row.get('desc') or ''
        short_desc = row.get('short_desc') or ''

        if short_desc and short_desc != desc:
            self.desc_text.insert('end', f"★ Özet: {short_desc}\n\n")

        if desc:
            self.desc_text.insert('end', desc)
        else:
            self.desc_text.insert('end', 'Bu varlık için ek metin açıklaması bulunamadı.')

        if is_bundle and row.get('parts'):
            self.desc_text.insert('end', '\n\n' + '—' * 28 + '\nPaket İçeriğindeki Eşyalar:\n')
            for part in row['parts']:
                pid = part['item_id']
                pname = self.item_names.get(pid, f"Eşya {pid}")
                days = part.get('days', 0)
                duration = 'Kalıcı' if days == 0 else f"{days} Günlük"
                self.desc_text.insert('end', f" • {pname} (ID #{pid}) — {duration}\n")

        self.btn_center_action.configure(text=f"➕ {name[:24]} Hesaba Ekle")

    def on_catalog_selected(self, _event=None):
        selection = self.asset_list.curselection()
        if not selection:
            return
        kind, row = self.display_rows[selection[0]]
        self.display_item_details(row, is_bundle=(kind == 'bundle'))

    def on_inventory_selected(self, _event=None):
        selection = self.inventory.selection()
        if not selection:
            return
        item_vals = self.inventory.item(selection[0], 'values')
        if not item_vals:
            return
        item_id = int(item_vals[0])
        row = self.item_lookup.get(item_id) or {
            'id': item_id,
            'name': item_vals[1],
            'category': item_vals[2],
            'desc': f"Hesabınızda kayıtlı eşya: {item_vals[1]} (Süre: {item_vals[3]})"
        }
        self.display_item_details(row, is_bundle=False)

    def show_inv_context_menu(self, event):
        row_id = self.inventory.identify_row(event.y)
        if row_id:
            if row_id not in self.inventory.selection():
                self.inventory.selection_set(row_id)
            self.inv_menu.post(event.x_root, event.y_root)

    def copy_selected_id(self):
        selection = self.inventory.selection()
        if selection:
            item_id = str(self.inventory.item(selection[0], 'values')[0])
            self.clipboard_clear()
            self.clipboard_append(item_id)
            self.status.configure(text=f"Eşya ID #{item_id} panoya kopyalandı.")

    def add_current_preview_item(self):
        if not self.current_preview_item:
            messagebox.showinfo('Eşya Seçin', 'Önce katalogdan eklemek istediğiniz bir eşyayı seçin.', parent=self)
            return
        user_id = self.selected_user_id()
        if not user_id:
            messagebox.showwarning('Hesap Gerekli', 'Lütfen önce yukarıdan bir oyuncu hesabı seçin.', parent=self)
            return

        item_id = int(self.current_preview_item['id'])
        item_ids = [] if self.current_preview_is_bundle else [item_id]
        bundle_ids = [item_id] if self.current_preview_is_bundle else []

        try:
            result = self.request('adminaddassets', body={'user_id': user_id, 'item_ids': item_ids, 'bundle_ids': bundle_ids}, timeout=20)
            if not result.get('ok'):
                raise RuntimeError(result.get('error', 'save-failed'))
            self.refresh_inventory()
            name = self.current_preview_item.get('name', f"Eşya {item_id}")
            self.status.configure(text=f"'{name}' hesaba başarıyla eklendi.")
        except Exception as exc:
            messagebox.showerror('Kayıt Başarısız', str(exc), parent=self)

    def add_selected(self):
        user_id = self.selected_user_id()
        selected = self.asset_list.curselection()
        if not user_id or not selected:
            messagebox.showinfo('Seçim Gerekli', 'Önce hesap ve eklenecek en az bir eşya seçin.', parent=self)
            return
        item_ids, bundle_ids = [], []
        for index in selected:
            kind, row = self.display_rows[index]
            (bundle_ids if kind == 'bundle' else item_ids).append(int(row['id']))
        try:
            result = self.request('adminaddassets', body={
                'user_id': user_id, 'item_ids': item_ids, 'bundle_ids': bundle_ids,
            }, timeout=20)
            if not result.get('ok'):
                raise RuntimeError(result.get('error', 'save-failed'))
            self.refresh_inventory()
            messagebox.showinfo('Envanter Güncellendi',
                                f"{result['added']} eşya hesaba eklendi/güncellendi.\n"
                                f"{result['already_owned']} kalıcı eşya zaten envanterinizde bulunuyor.", parent=self)
            self.status.configure(text='Değişiklikler yerel sunucuya kaydedildi.')
        except Exception as exc:
            messagebox.showerror('Kayıt Başarısız', str(exc), parent=self)

    def remove_selected_inventory(self):
        """Delete selected items from inventory with user confirmation."""
        user_id = self.selected_user_id()
        selection = self.inventory.selection()
        if not user_id or not selection:
            messagebox.showinfo('Seçim Gerekli', 'Lütfen envanterden silmek istediğiniz eşyayı/eşyaları seçin.', parent=self)
            return

        item_ids = [int(self.inventory.item(item, 'values')[0]) for item in selection]
        count = len(item_ids)

        if count == 1:
            name = self.inventory.item(selection[0], 'values')[1]
            confirm_msg = f"'{name}' (ID #{item_ids[0]}) eşyasını envanterden silmek istediğinize emin misiniz?"
        else:
            confirm_msg = f"Seçilen {count} adet eşyayı envanterden silmek istediğinize emin misiniz?"

        if not messagebox.askyesno('Envanterden Sil', confirm_msg, parent=self):
            return

        try:
            result = self.request('adminremoveassets', body={'user_id': user_id, 'item_ids': item_ids}, timeout=10)
            if not result.get('ok'):
                raise RuntimeError(result.get('error', 'remove-failed'))

            # Also sync with LAN server SQLite store if present
            sync_sqlite_removal(self.selected_username(), item_ids)

            self.refresh_inventory()
            self.status.configure(text=f"{result.get('removed', count)} adet eşya envanterden silindi.")
        except Exception as exc:
            messagebox.showerror('Silme Başarısız', f"Eşyalar silinirken hata oluştu: {exc}", parent=self)

    def clear_all_inventory(self):
        """Clear all items from player's inventory."""
        user_id = self.selected_user_id()
        if not user_id:
            return
        username = self.selected_username()

        confirm = messagebox.askyesno(
            'Tüm Envanteri Temizle',
            f"DİKKAT!\n'{username}' kullanıcısının TÜM envanter eşyaları kalıcı olarak silinecektir.\n\nDevam etmek istiyor musunuz?",
            icon='warning',
            parent=self
        )
        if not confirm:
            return

        try:
            result = self.request('adminclearinventory', body={'user_id': user_id}, timeout=10)
            if not result.get('ok'):
                raise RuntimeError(result.get('error', 'clear-failed'))

            sync_sqlite_clear(username)
            self.refresh_inventory()
            self.status.configure(text=f"'{username}' hesabının tüm envanteri temizlendi.")
            messagebox.showinfo('Envanter Temizlendi', 'Hesaptaki tüm eşyalar başarıyla silindi.', parent=self)
        except Exception as exc:
            messagebox.showerror('Hata', f"Envanter temizlenirken hata oluştu: {exc}", parent=self)

    def refresh_inventory(self):
        user_id = self.selected_user_id()
        if not user_id:
            return
        try:
            rows = self.request('admininventory', {'id': user_id})
        except Exception as exc:
            self.status.configure(text=f'Envanter okunamadı: {exc}')
            return

        self.owned = {}
        self.inventory.delete(*self.inventory.get_children())
        total_qty = 0

        for row in rows:
            item_id = int(row['item_id'])
            self.owned[item_id] = True
            item_meta = self.item_lookup.get(item_id, {})
            name = item_meta.get('name') or self.item_names.get(item_id) or f"Eşya {item_id}"
            category = item_meta.get('category', 'Eşya')
            expiry = 'Kalıcı' if row['expiry'] == 'permanent' else row['expiry']
            qty = int(row['quantity'])
            total_qty += qty
            self.inventory.insert('', 'end', values=(item_id, name, category, expiry, qty))

        self.inv_count_label.configure(text=f"{len(rows)} Çeşit / {total_qty} Adet")

    def refresh_all(self):
        try:
            self.account_rows = self.request('adminusers')
            current = self.selected_user_id()
            self.account['values'] = [f"{row['username']}  (#{row['id']})" for row in self.account_rows]
            found = next((i for i, row in enumerate(self.account_rows) if int(row['id']) == current), 0)
            if self.account_rows:
                self.account.current(found)
                self.refresh_inventory()
            self.status.configure(text='Hesap ve envanter listesi yenilendi.')
        except Exception as exc:
            self.status.configure(text=f'Yenileme başarısız: {exc}')
        self.refresh_catalog()

    def close(self):
        if self.host_process is not None and self.host_process.poll() is None:
            (self.host_run / 'stop.request').write_text('stop', encoding='ascii')
            try:
                self.host_process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self.host_process.terminate()
        for handle in getattr(self, '_host_logs', ()):
            handle.close()
        self.destroy()


if __name__ == '__main__':
    if not CATALOG_PATH.exists():
        raise SystemExit('2012 asset catalog is missing: run logan/tools/import_asset_catalog.py')
    InventoryManager().mainloop()


