#!/usr/bin/env python3
"""Recheck speed, reverse gearing, and fuel-cut timing from local raw captures."""

import argparse
from bisect import bisect_left, bisect_right
from collections import defaultdict
from itertools import groupby
from pathlib import Path
from statistics import median
from struct import unpack

from review_remaining import frames


def word(data):
    return int.from_bytes(data[:2], "little")


def recent(series, t, max_age):
    times, values = series
    i = bisect_right(times, t) - 1
    if i >= 0 and t - times[i] <= max_age:
        return values[i]


def speed_and_reverse(series):
    wheel_times, wheels = series[0x0D4]
    matched = one_stopped = errors = unmatched = 0
    ratios = []
    for t, speed in zip(*series[0x0D1]):
        raw = word(speed)
        i = bisect_left(wheel_times, t)
        if i < len(wheels) and wheel_times[i] - t <= 5000:
            left, right = unpack("<HH", wheels[i][:4])
            expected = (left + right) // 2 if left and right else 0
            one_stopped += (left == 0) != (right == 0)
            matched += 1
            errors += expected != raw
        else:
            unmatched += 1

        engine = recent(series[0x140], t, 20000)
        switches = recent(series[0x152], t, 40000)
        speed_kmh = raw * 0.05625
        if engine and switches and speed_kmh >= 4 and switches[6] & 4 and not engine[1] & 128:
            rpm = word(engine[2:4]) & 0x3FFF
            ratios.append(rpm / speed_kmh)
    print(f"  Speed: {matched} pairs, {one_stopped} with one front stopped,"
          f" {errors} mismatches, {unmatched} unpaired")
    if ratios:
        print(f"  Reverse: {len(ratios)} samples, median {median(ratios):.3f} rpm/(km/h)")


def fuel_cut(series):
    times, engine = series[0x141]
    flow_times, flow = series[0x360]
    cuts = starts_flagged = overlaps = 0
    exit_delays, flow_delays = [], []
    for cutting, group in groupby(range(len(engine)), lambda i: bool(engine[i][7] & 8)):
        if not cutting:
            continue
        indices = list(group)
        cuts += 1
        starts_flagged += bool(engine[indices[0]][6] & 32)
        flagged = [i for i in indices if engine[i][6] & 32]
        if not flagged:
            continue
        overlaps += 1
        edge = times[flagged[0]]
        end = indices[-1] + 1
        if end < len(times):
            exit_delays.append((times[end] - edge) / 1000)
        i = bisect_right(flow_times, edge)
        if i and word(flow[i - 1]) == 0:
            first = next((j for j in range(i, len(flow)) if word(flow[j])), None)
            if first is not None:
                flow_delays.append((flow_times[first] - edge) / 1000)
    print(f"  Fuel cut: {cuts} runs, {starts_flagged} began with b6.5 set,"
          f" {overlaps} overlapped b6.5")
    for label, delays in [("cut exit", exit_delays), ("positive fuel flow", flow_delays)]:
        if delays:
            print(f"    {label}: {len(delays)} events, {min(delays):.1f}..{max(delays):.1f} ms")


def review(path):
    series = defaultdict(lambda: ([], []))
    count = 0
    for t, can_id, data in frames(path / "raw.bin"):
        count += 1
        if can_id in (0x0D1, 0x0D4, 0x140, 0x141, 0x152, 0x360):
            times, values = series[can_id]
            if times and times[-1] > t:
                raise ValueError(f"Split {path} at timestamp wrap before matching frames")
            times.append(t)
            values.append(data)
    print(f"{path.name}: {count} frames")
    speed_and_reverse(series)
    fuel_cut(series)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("recordings", type=Path, help="recordings directory or one capture")
    root = parser.parse_args().recordings
    captures = [root / "raw.bin"] if (root / "raw.bin").is_file() else sorted(root.glob("*/raw.bin"))
    if not captures:
        parser.error(f"No raw recordings in {root}")
    for capture in captures:
        review(capture.parent)
