#!/usr/bin/env python3
"""
Считает, у скольких снимков есть координаты и дата съёмки в EXIF.

Ничего устанавливать не нужно: работает на том Python, который уже есть
в macOS. Читает только начало каждого файла, поэтому по сети идёт быстро.

Запуск:
    python3 gps_audit.py /путь/к/папке
"""

import os
import struct
import sys

HEADER = 128 * 1024
EXTS = (".jpg", ".jpeg", ".jpe")

TAG_EXIF_IFD = 0x8769
TAG_GPS_IFD = 0x8825
TAG_GPS_LAT = 0x0002
TAG_DATETIME_ORIGINAL = 0x9003
TAG_DATETIME = 0x0132


def find_exif(data):
    """Возвращает срез с TIFF-заголовком EXIF или None."""
    if data[0:2] != b"\xff\xd8":
        return None
    i = 2
    n = len(data)
    while i + 4 <= n:
        if data[i] != 0xFF:
            i += 1
            continue
        marker = data[i + 1]
        if marker in (0xD8, 0x01) or 0xD0 <= marker <= 0xD7:
            i += 2
            continue
        if marker == 0xDA:  # начало данных изображения — метаданные позади
            return None
        seg_len = struct.unpack(">H", data[i + 2:i + 4])[0]
        seg = data[i + 4:i + 2 + seg_len]
        if marker == 0xE1 and seg[:6] == b"Exif\x00\x00":
            return seg[6:]
        i += 2 + seg_len
    return None


def read_ifd(tiff, offset, endian, wanted):
    """Возвращает {tag: (type, count, value_or_offset)} для нужных тегов."""
    out = {}
    if offset + 2 > len(tiff):
        return out
    count = struct.unpack(endian + "H", tiff[offset:offset + 2])[0]
    for k in range(count):
        entry = offset + 2 + k * 12
        if entry + 12 > len(tiff):
            break
        tag, typ, cnt = struct.unpack(endian + "HHI", tiff[entry:entry + 8])
        raw = tiff[entry + 8:entry + 12]
        if tag in wanted:
            out[tag] = (typ, cnt, raw)
    return out


def analyse(path):
    """(есть_координаты, есть_дата) для одного файла."""
    try:
        with open(path, "rb") as f:
            data = f.read(HEADER)
    except OSError:
        return None

    tiff = find_exif(data)
    if tiff is None:
        return (False, False)

    if tiff[:2] == b"II":
        endian = "<"
    elif tiff[:2] == b"MM":
        endian = ">"
    else:
        return (False, False)

    try:
        ifd0_off = struct.unpack(endian + "I", tiff[4:8])[0]
        top = read_ifd(tiff, ifd0_off, endian,
                       {TAG_EXIF_IFD, TAG_GPS_IFD, TAG_DATETIME})

        has_date = TAG_DATETIME in top
        has_gps = False

        if TAG_GPS_IFD in top:
            gps_off = struct.unpack(endian + "I", top[TAG_GPS_IFD][2])[0]
            gps = read_ifd(tiff, gps_off, endian, {TAG_GPS_LAT})
            has_gps = TAG_GPS_LAT in gps

        if TAG_EXIF_IFD in top:
            exif_off = struct.unpack(endian + "I", top[TAG_EXIF_IFD][2])[0]
            sub = read_ifd(tiff, exif_off, endian, {TAG_DATETIME_ORIGINAL})
            has_date = has_date or TAG_DATETIME_ORIGINAL in sub

        return (has_gps, has_date)
    except (struct.error, IndexError):
        return (False, False)


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return

    root = sys.argv[1]
    per_folder = {}
    total = gps = dated = unreadable = 0

    for dirpath, _dirnames, filenames in os.walk(root):
        for name in filenames:
            if not name.lower().endswith(EXTS):
                continue
            result = analyse(os.path.join(dirpath, name))
            total += 1
            if result is None:
                unreadable += 1
                continue
            has_gps, has_date = result
            gps += has_gps
            dated += has_date

            folder = os.path.relpath(dirpath, root)
            stat = per_folder.setdefault(folder, [0, 0])
            stat[0] += 1
            stat[1] += has_gps

            if total % 500 == 0:
                print(f"  ...просмотрено {total}", file=sys.stderr)

    print()
    print(f"Всего снимков JPEG : {total}")
    print(f"С координатами     : {gps}")
    print(f"С датой съёмки     : {dated}")
    if unreadable:
        print(f"Не удалось открыть : {unreadable}")

    print()
    print("По папкам (координаты / всего):")
    rows = sorted(per_folder.items(), key=lambda kv: -kv[1][0])
    for folder, (cnt, with_gps) in rows[:30]:
        print(f"  {with_gps:6d} / {cnt:6d}   {folder}")


if __name__ == "__main__":
    main()
