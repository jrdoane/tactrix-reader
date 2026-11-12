#!/usr/bin/env python3
"""Read-only checks of body parity and steering packing (Python 3.10+).

Run with a recordings directory, or individual recording directories:
  python3 analysis/review_remaining.py ~/.tactrix-reader/recordings
Reads raw.bin, not exported CSVs. No vehicle connection or third-party packages.
"""

import argparse
import bisect
from collections import Counter, defaultdict
from pathlib import Path
import statistics
import struct


def frames(path):
    raw = path.read_bytes()
    pos = 0
    while pos < len(raw):
        if raw[pos:pos + 3] != b"ar5":
            pos += 1  # ASCII command replies outside binary frames
            continue
        if pos + 4 > len(raw):
            raise ValueError(f"Truncated header in {path}")
        size = raw[pos + 3]
        payload = raw[pos + 4:pos + 4 + size]
        if not 9 <= size <= 17 or len(payload) != size:
            raise ValueError(f"Invalid/truncated raw CAN frame at {pos} in {path}")
        pos += 4 + size
        status, timestamp, can_id = struct.unpack(">BII", payload[:9])
        if status == 0 and can_id:
            yield timestamp, can_id, payload[9:]


def signed(value, width):
    half = 1 << (width - 1)
    return (value + half) % (1 << width) - half


def review(folder):
    selected = defaultdict(list)
    count = 0
    for timestamp, can_id, data in frames(folder / "raw.bin"):
        count += 1
        if can_id in (0x370, 0x371, 0x374):
            if len(data) != 8:
                raise ValueError(f"Unexpected DLC for {can_id:03X}")
            selected[can_id].append((timestamp, data))
    print(f"\n{folder.name}: {count:,} raw frames")
    body = [data for _, data in selected[0x374]]
    parity = Counter(sum(byte.bit_count() for byte in data) % 2 for data in body)
    doors = Counter((data[3] & 15).bit_count() for data in body)
    print(f"  374 parity: {dict(parity)} (1 = odd); open-door counts: {dict(doors)}")
    if not selected[0x371]:
        return
    times = [timestamp for timestamp, _ in selected[0x371]]
    if times != sorted(times):
        raise ValueError("Clock wrap/jump: split the recording before comparing")
    x, y = [], []
    byte3 = Counter()
    torque_by_flag = defaultdict(list)
    for timestamp, data in selected[0x370]:
        byte3[data[3]] += 1
        value = signed(data[4], 8)
        torque_by_flag[bool(data[3] & 64)].append(abs(value))
        index = bisect.bisect_right(times, timestamp) - 1
        if index < 0 or timestamp - times[index] > 25_000:
            continue
        word = int.from_bytes(selected[0x371][index][1], "little")
        x.append(value)
        y.append(signed((word >> 29) & 2047, 11))
    print(f"  370 byte 3: {dict(sorted(byte3.items()))}")
    if len(set(x)) > 1 and len(set(y)) > 1:
        slope, intercept = statistics.linear_regression(x, y)
        print(f"  371 torque ~ {slope:.4f} * signed(370 byte 4) {intercept:+.3f};"
              f" r={statistics.correlation(x, y):.5f}, n={len(x)}")
    for flag, values in sorted(torque_by_flag.items()):
        print(f"  370 b3.6={int(flag)}: n={len(values)},"
              f" abs(byte 4) range={min(values)}..{max(values)}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("paths", nargs="+", type=Path)
    args = parser.parse_args()
    for path in args.paths:
        folders = [path] if (path / "raw.bin").is_file() else sorted(
            p for p in path.iterdir() if (p / "raw.bin").is_file())
        if not folders:
            parser.error(f"No recordings found in {path}")
        for folder in folders:
            review(folder)


if __name__ == "__main__":
    main()
