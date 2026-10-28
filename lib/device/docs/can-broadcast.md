# Broadcast CAN traffic

The OBD port carries the car's own CAN traffic at 500 kbps. A pass-all filter
receives it without sending anything, and every [recording](recordings.md)
captures it in `raw.bin`. This page records what that traffic contains on the
project's 2015 Impreza (manual).

Everything here comes from one recording, `2026-10-24-idle`: 60 seconds of a
warm engine idling in neutral with the car parked. Speed, pedal, brake,
steering, gear, and lights never change in it, so signals for those can't be
confirmed from it. Engine speed wanders between 633 and 662 rpm, and the
idle controller trims timing to hold it, so at idle almost every varying
engine byte moves with RPM one way or the other.

Signal names marked **opendbc** come from comma.ai's
[`_subaru_preglobal_2015.dbc`](https://github.com/commaai/opendbc/blob/master/opendbc/dbc/generator/subaru/_subaru_preglobal_2015.dbc),
written for the 2015-19 Outback and Legacy, plus the `371` definition from
[`subaru_outback_2015.dbc`](https://github.com/commaai/opendbc/blob/master/opendbc/dbc/generator/subaru/subaru_outback_2015.dbc)
in the same directory. Those definitions match this car's IDs and the signals
checked below, but any not marked confirmed are unconfirmed on this car.

Bytes are numbered from 0, bits from 0 at the least significant end, and
`b5.6` means byte 5 bit 6. "LE" means little-endian. Bit positions written
`16-29` count from bit 0 of byte 0, least significant first, as opendbc does.

## Message list

24 IDs besides the `7E0` requests and `7E8` replies: about 880 frames per
second.

| ID | Period | DLC | opendbc name | Notes |
|---|---|---|---|---|
| `002` | 10 ms | 8 | Steering | Counter and checksum confirmed |
| `0D0` | 20 ms | 8 | G_Sensor | Lateral fits opendbc; longitudinal doesn't |
| `0D1` | 20 ms | 4 | Brake_Pedal | Speed and brake pedal; all zero |
| `0D2` | 20 ms | 8 | Brake_2 | Constant |
| `0D3` | 20 ms | 7 | Brake_Type | Byte 6 is a counter |
| `0D4` | 20 ms | 8 | Wheel_Speeds | All zero |
| `140` | 10 ms | 8 | Throttle | **Engine RPM confirmed**; counter, parity bit |
| `141` | 10 ms | 8 | Engine | Low-pass filtered RPM; parity bit |
| `144` | 20 ms | 8 | CruiseControl | Byte 2 rises with RPM; parity bit |
| `152` | 20 ms | 8 | Stalk | Lights and wipers; counter, parity bit |
| `154` | 1 s | 7 | | Constant |
| `156` | 20 ms | 8 | | Constant |
| `282` | 50 ms | 8 | Dashlights | Blinkers, seatbelt; counter |
| `360` | 50 ms | 8 | Engine_Temp | **Oil and coolant temperature confirmed**; byte 0 tracks RPM |
| `361` | 50 ms | 8 | | Counter, parity bit, noisy 12-bit value |
| `370` | 50 ms | 8 | Steering_Torque_2 | Counter; coarse copy of `371`'s torque |
| `371` | 20 ms | 8 | Steering_Torque | From the Outback 2015 file |
| `372` | 100 ms | 8 | | Counter and parity bit in byte 1 |
| `374` | 1 s | 8 | BodyInfo | Doors |
| `375` | 1 s | 8 | | Constant |
| `376` | 50 ms | 1 | | Constant `FA` |
| `3D1` | 100 ms | 8 | Dash_State2 | Byte 0 low bits wander 2-5 |
| `660` | 500 ms | 8 | | 0.1 s timer in bytes 6-7 |
| `6FA` | 1 s | 5 | | Byte 0 low nibble is a counter |

opendbc's `148` (Transmission, with the automatic's gear) and `149`
(CVT_Ratio) are absent, as expected on a manual. So are the EyeSight
messages (`160`-`167`) and `362` (Fuel).

Periods are steady: most IDs' inter-frame gaps stay within about 3% of the
nominal period. `282` and `371` jitter by about 5%, `156` by about 10%, and
the 10 ms engine messages `140` and `141` range 8.5-11.4 ms.

## Confirmed against polled PIDs

These were checked against the mode 01 replies recorded alongside them,
taking the latest broadcast frame before each reply:

| Signal | Encoding | Check |
|---|---|---|
| Engine RPM | `140`: bits 16-29 LE (byte 2, plus byte 3 & `3F`), 1 rpm per bit | Within 2.5 rpm of PID `0C` in all 120 samples; the broadcast value is whole rpm |
| Oil temperature | `360` byte 2, minus 40, in °C | Equal to PID `5C` in all 120 samples |
| Coolant temperature | `360` byte 3, minus 40, in °C | Equal to PID `05` in 119 of 120 samples, 1 °C off in the other |

No other byte matches a polled PID. In particular nothing broadcast tracks
engine load (`04`), MAF (`10`), manifold pressure (`0B`), battery voltage
(`42`), intake air temperature (`0F`), or the fuel trims (`06`, `07`):
no field correlates with any of those beyond |r| = 0.37, against 0.8-0.99
for the RPM-linked fields below.

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
`141`'s filtered RPM (below) is deliberately not decoded: it would report
as PID `0C` too, two seconds behind.

The IDs and layouts are this car's. On another vehicle the same IDs may carry
something else, so pass `:broadcast {}` to the sources there.

## Message structure

Confirmed from the frames alone:

- **`002` checksum:** byte 4 is the sum of the other seven bytes, mod 256, in
  all 6,033 frames. No other ID has a sum or XOR checksum byte.
- **Parity bits:** in `140`, `141`, `144`, `152`, `361`, and `372`, every
  frame has an odd number of set bits across all eight data bytes. The bit
  that carries no data and flips to keep that true is `140` b7.7, `141`
  b5.6, `144` b1.0, `152` b0.3, `361` b1.4, and `372` b1.3. These are the
  bits an earlier pass listed as "toggling"; their toggle rates were just
  the parity of the counters and values around them. The other IDs with
  varying bytes (`002`, `0D0`, `0D3`, `282`, `360`, `370`, `371`, `3D1`,
  `660`, `6FA`) have no parity bit.
- **Rolling counters:** `002` byte 3 bits 1-3 (3 bits, bit 0 always set);
  `0D3` byte 6 (full byte); `140` byte 1 low nibble; `152` and `282` byte 1
  high nibble; `361` byte 4 low nibble; `370` byte 5 low nibble; `372`
  byte 1 high nibble; `6FA` byte 0 low nibble. Each increments by one per
  frame and wraps.
- **`660` timer:** bytes 6-7, big-endian, increase by exactly 10 per second,
  so it counts tenths of a second: byte 6 stepped at 18.6 s and again 25.6 s
  later. It read 21,575 (36 minutes) at the start. What it counts from is
  unknown; time since ignition on is a guess.

### `141` carries a filtered copy of engine speed

`141` bits 32-43 (byte 4, plus byte 5 & `0F`), which opendbc calls
Engine_RPM, is `140`'s RPM through a first-order low-pass filter with a
2.0 s time constant: `y += 0.005 × (rpm − y)` per 10 ms `140` frame, then
rounded. Computed that way from the recorded `140` frames, it equals the
`141` value exactly in 96% of frames after the filter settles, and is never
more than 1 rpm off. A time constant of 1.8 or 2.2 s leaves errors above
1 rpm, and a plain moving average fits worse at any window. The means agree
to 0.3 rpm, so the scale is 1 rpm per bit with no offset.

### Engine bytes that move with idle speed

These vary with RPM but aren't RPM, and no polled PID pins them down:

- **`141` byte 2** (59-64) falls as RPM rises, from 59 at 656-662 rpm to 64
  at 633-636 rpm. It tracks timing advance: PID `0E` (10-14° here) is
  byte 2 minus 49, within 1°, in every sample. opendbc puts this byte in a
  12-bit Wheel_Torque field (bits 16-27; byte 3 is a constant `27`).
- **`144` byte 2** (60-63) rises with RPM: 60 at 633-645 rpm, 61 at
  642-653, 62 at 650-662, 63 at 662. The two bytes mirror each other:
  `141` byte 2 plus `144` byte 2 is 121-124 in every `144` frame, 122 in
  most. opendbc has no signal for this byte.
- **`360` byte 0** (165-175) rises and falls with the RPM of the same
  instant (r = 0.80 against the latest `140` frame, worse with any lag or
  smoothing), about 0.26 counts per rpm, and is smooth from frame to frame.
  It dipped to 165 during the 12-15 s RPM dip. opendbc has no signal for it.

Byte 2 of `141` and byte 0 of `360` are candidates for something the idle
controller changes, such as commanded torque; a recording with throttle blips
would separate them from RPM.

### Other varying bytes, unexplained

- **`361`:** byte 5's low nibble is always 0, and its high nibble plus
  byte 6 look like one noisy 12-bit value (1879-2278 with byte 6 as the high
  byte), nearly uncorrelated from one 50 ms frame to the next and with no
  polled PID. Byte 7 read `52` for the first 11 s, flickered between `51`
  and `52` until 16 s, then stayed `51`. Byte 1 bit 4 is the parity bit, not
  a signal.
- **`282` byte 0** (49-56) jitters around 51-52 with no PID correlation.
  Byte 4 stepped from `7D` to `7E` at 31.6 s and back at 38.0 s.
- **`3D1` byte 0** low three bits wander between 2 and 5 over seconds.
- **`140` byte 6** is a constant 9. opendbc calls it Throttle_Body, but
  PID `11` (throttle, raw 35) and PID `45` (relative throttle, raw 6) are
  both constant and neither is 9.
- **`141` bytes 0-1** are a constant `65 26`. opendbc's 15-bit Engine_Torque
  there would read 9829, which doesn't look like idle torque.

## Not testable in this recording

opendbc signals whose values here fit a parked, idling car, but which never
change:

- **Wheel speeds (`0D4`):** four 16-bit LE values, 0.0592 km/h per bit. All
  zero.
- **Vehicle speed (`0D1`):** bytes 0-1 LE, 0.05625 km/h per bit; brake pedal
  in byte 2. All zero.
- **Lateral acceleration (`0D0`):** bytes 2-3, signed LE. It reads 0 or ±1
  raw, consistent with sensor noise at rest.
- **Throttle pedal (`140` byte 0):** 0, matching PID `5A`'s 0%. Byte 3
  bit 6, opendbc's Off_Throttle, is set.
- **Cruise control (`144`), lights and wipers (`152`), blinkers (`282`):**
  off.

### Steering torque (`370`, `371`)

The Outback 2015 file lays `371` out as Steering_Motor_Flat (bits 0-9),
Steer_Torque_Output (bits 16-26, signed), Steer_Error_1 (bit 27),
Steer_Torque_Sensor (bits 29-39, signed), and Steering_Angle (bytes 5-6,
signed LE, −0.033° per bit). `370` has Steering_Voltage_Flat (byte 0) and
Steer_Torque_Sensor at the same bits 29-39. Reading this car's frames that
way:

- `371` Steer_Torque_Output is −1 in 95% of frames, otherwise 0 or −2 to −4.
  Bits 27 and 28 are never set, so byte 3's low nibble is `7` (the sign
  bits of a negative output) or `0`.
- `371` Steer_Torque_Sensor jitters between −28 and −27, with excursions to
  −39 and −7. `370`'s is −32 in 96% of frames and only ever a multiple of 8,
  because bits 29-31 are always 0: it is the same value at byte resolution.
  Byte 4 of the two messages is equal in 1,191 of 1,206 `370` frames and
  one apart in the other 15.
- `371` Steering_Angle is a constant 310 raw, −10.2°.
- `370` byte 0 (0-9) and `371` byte 0 (0 or 1, rarely 3 or 4) are both
  white noise from frame to frame, and independent of each other.

Whether these are steering torque can't be told without turning the wheel.

### Disagreements

- **Steering angle.** opendbc defines three, and they disagree: `002` reads
  −8.3° (bytes 0-1 big-endian, 0.1° per bit), `0D0` reads −5.9° (bytes 0-1
  LE, −0.1° per bit), and `371` reads −10.2°, all constant. The car's actual
  wheel position wasn't noted. Recording with the wheel held at known angles
  would show which scale is right.
- **Longitudinal acceleration (`0D0`).** opendbc reads bytes 6-7 as signed
  LE. Here byte 7 behaves like the lateral channel, taking `00`, `FF`, or
  `01` (0, −1, +1), while byte 6 is 2 in 70% of frames and 1 in the rest,
  usually for one frame at a time, independently of byte 7. As a 16-bit LE
  value that would be jumps of 256, so on this car byte 6 is something
  else, and byte 7 alone is the resting noise of an acceleration channel.
- **Doors (`374`).** opendbc's DOOR_OPEN_FR bit (byte 3 bit 0) is set for the
  whole recording. Either a door was open or the bit means something else on
  this car.

## Next recording

A recording that varies these signals one at a time, with a note of when,
would confirm most of the above. `record` already captures broadcast traffic
alongside its PID polling:

1. Parked, engine running: hold the wheel straight, then at full lock each
   way; press the brake; blip the throttle (this separates `141` byte 2,
   `144` byte 2, and `360` byte 0 from RPM, and tests `141`'s 2 s filter);
   open each door; turn on each blinker and the headlights.
2. A short drive through each gear, for wheel speeds, vehicle speed,
   acceleration, and whatever on this manual reports the gear.
