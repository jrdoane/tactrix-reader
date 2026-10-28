# Broadcast CAN traffic

The OBD port carries the car's own CAN traffic at 500 kbps. A pass-all filter
receives it without sending anything, and every [recording](recordings.md)
captures it in `raw.bin`. This page records what that traffic contains on the
project's 2015 Impreza (manual).

Everything here comes from one recording, `2026-10-24-idle`: 60 seconds of a
warm engine idling in neutral with the car parked. Speed, pedal, brake,
steering, gear, and lights never change in it, so signals for those can't be
confirmed from it.

Signal names marked **opendbc** come from comma.ai's
[`_subaru_preglobal_2015.dbc`](https://github.com/commaai/opendbc/blob/master/opendbc/dbc/generator/subaru/_subaru_preglobal_2015.dbc),
written for the 2015-19 Outback and Legacy. Those definitions match this car's
IDs and the signals checked below, but any not marked confirmed are
unconfirmed on this car.

Bytes are numbered from 0. "LE" means little-endian.

## Message list

24 IDs besides the `7E0` requests and `7E8` replies: about 880 frames per
second.

| ID | Period | DLC | opendbc name | Notes |
|---|---|---|---|---|
| `002` | 10 ms | 8 | Steering | Counter and checksum confirmed |
| `0D0` | 20 ms | 8 | G_Sensor | |
| `0D1` | 20 ms | 4 | Brake_Pedal | Speed and brake pedal; all zero |
| `0D2` | 20 ms | 8 | Brake_2 | Constant |
| `0D3` | 20 ms | 7 | Brake_Type | Byte 6 is a counter |
| `0D4` | 20 ms | 8 | Wheel_Speeds | All zero |
| `140` | 10 ms | 8 | Throttle | **Engine RPM confirmed** |
| `141` | 10 ms | 8 | Engine | Second RPM value |
| `144` | 20 ms | 8 | CruiseControl | |
| `152` | 20 ms | 8 | Stalk | Lights and wipers |
| `154` | 1 s | 7 | | Constant |
| `156` | 20 ms | 8 | | Constant |
| `282` | 50 ms | 8 | Dashlights | Blinkers, seatbelt |
| `360` | 50 ms | 8 | Engine_Temp | **Oil and coolant temperature confirmed** |
| `361` | 50 ms | 8 | | Byte 4 low nibble is a counter |
| `370` | 50 ms | 8 | Steering_Torque_2 | |
| `371` | 20 ms | 8 | Steering_Torque | |
| `372` | 100 ms | 8 | | Byte 1 cycles through 16 values |
| `374` | 1 s | 8 | BodyInfo | Doors |
| `375` | 1 s | 8 | | Constant |
| `376` | 50 ms | 1 | | Constant `FA` |
| `3D1` | 100 ms | 8 | Dash_State2 | |
| `660` | 500 ms | 8 | | 0.1 s timer in bytes 6-7 |
| `6FA` | 1 s | 5 | | Byte 0 low nibble is a counter |

opendbc's `148` (Transmission, with the automatic's gear) and `149`
(CVT_Ratio) are absent, as expected on a manual. So are the EyeSight
messages (`160`-`167`).

## Confirmed against polled PIDs

These were checked against the mode 01 replies recorded alongside them,
taking the latest broadcast frame before each reply:

| Signal | Encoding | Check |
|---|---|---|
| Engine RPM | `140`: bits 16-29 LE (byte 2, plus byte 3 & `3F`), 1 rpm per bit | Within 2.5 rpm of PID `0C` in all 120 samples; the broadcast value is whole rpm |
| Oil temperature | `360` byte 2, minus 40, in °C | Equal to PID `5C` in all 120 samples |
| Coolant temperature | `360` byte 3, minus 40, in °C | Equal to PID `05` in 119 of 120 samples, 1 °C off in the other |

`141` bits 32-43 also hold an RPM-like value (642-659 rpm against PID `0C`'s
635-660), which opendbc calls Engine_RPM. It differs from PID `0C` by up to
14.5 rpm, so it may be filtered or a target speed; not confirmed.

### Decoding

`broadcast-signals` maps these three signals to the PIDs they match, and
`broadcast-samples` turns a received message into samples shaped like PID
samples, with the CAN ID added:

```clojure
{:pid 0x0C :name :engine-rpm :value 658.0 :unit :rpm :can-id 0x140 :timestamp-us 2181426917}
```

The live and replay sources emit them alongside polled PIDs, so consumers
keyed by name or PID see one value for each. Broadcast RPM arrives every
10 ms and the temperatures every 50 ms, so they usually carry the latest
value. Only add a signal to `broadcast-signals` once it matches a polled PID.

The IDs and layouts are this car's. On another vehicle the same IDs may carry
something else, so pass `:broadcast {}` to the sources there.

## Message structure

Confirmed from the frames alone:

- **`002` checksum:** byte 4 is the sum of the other seven bytes, mod 256, in
  all 6,033 frames.
- **Rolling counters:** `002` byte 3 bits 1-3 (3 bits, bit 0 always set);
  `0D3` byte 6 (full byte); `140` byte 1 low nibble; `152` and `282` byte 1
  high nibble; `361` byte 4 low nibble; `370` byte 5 low nibble; `6FA` byte 0
  low nibble. Each increments by one per frame and wraps.
- **`660` timer:** bytes 6-7, big-endian, increase by exactly 10 per second,
  so it counts tenths of a second. It read 21,575 (36 minutes) at the start.
  What it counts from is unknown; time since ignition on is a guess.
- **Toggling bits:** `140` byte 7 bit 7, `141` byte 5 bit 6, `152` byte 0
  bit 3, and `361` byte 1 bit 4 each change between frames. Their pattern
  hasn't been examined.

## Not testable in this recording

opendbc signals whose values here fit a parked, idling car, but which never
change:

- **Wheel speeds (`0D4`):** four 16-bit LE values, 0.0592 km/h per bit. All
  zero.
- **Vehicle speed (`0D1`):** bytes 0-1 LE, 0.05625 km/h per bit; brake pedal
  in byte 2. All zero.
- **Lateral acceleration (`0D0`):** bytes 2-3, signed LE. It reads 0 or ±1
  raw, consistent with sensor noise at rest.
- **Steering torque (`370`, `371`):** near zero.
- **Cruise control (`144`), lights and wipers (`152`), blinkers (`282`):**
  off.

### Disagreements

- **Steering angle.** opendbc defines three, and they disagree: `002` reads
  −8.3°, `0D0` reads −5.9°, and `371` reads −10.2°, all constant. The
  car's actual wheel position wasn't noted. Recording with the wheel held at
  known angles would show which scale is right.
- **Longitudinal acceleration (`0D0`).** opendbc reads bytes 6-7 as signed
  LE, but byte 6 alternates between 1 and 2 while byte 7 is `00`, `FF`, or
  `01`. That decodes to values 256 apart, which doesn't look like noise at
  rest, so the byte order or layout may differ on this car.
- **Doors (`374`).** opendbc's DOOR_OPEN_FR bit (byte 3 bit 0) is set for the
  whole recording. Either a door was open or the bit means something else on
  this car.

## Next recording

A recording that varies these signals one at a time, with a note of when,
would confirm most of the above. `record` already captures broadcast traffic
alongside its PID polling:

1. Parked, engine running: hold the wheel straight, then at full lock each
   way; press the brake; blip the throttle; open each door; turn on each
   blinker and the headlights.
2. A short drive through each gear, for wheel speeds, vehicle speed,
   acceleration, and whatever on this manual reports the gear.
