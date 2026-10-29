# Broadcast CAN traffic

The OBD port carries the car's own CAN traffic at 500 kbps. A pass-all filter
receives it without sending anything, and every [recording](recordings.md)
captures it in `raw.bin`. This page records what that traffic contains on the
project's 2015 Impreza (manual).

It comes from two recordings, each polling mode 01 PIDs alongside:

- **`2026-10-24-idle`:** 60 seconds of a warm engine idling in neutral, parked.
  Engine speed wanders between 633 and 662 rpm and nothing else changes.
- **`2026-10-28-drive`:** 9 minutes 17 seconds, 561,400 frames. It starts
  with the ignition on and the engine off (coolant 47 °C), the engine starts
  at 5 s, and it covers town and highway driving through all five gears up to
  139 km/h, a full-throttle pull to 6,400 rpm, braking to several stops, and a
  warm idle in neutral with the parking brake on at the end. When the wheel
  was turned, doors opened, or lights switched wasn't noted.

Findings that rest on one recording say which. The drive overturned some idle
findings: at idle almost every varying engine byte moved with RPM, and most
of those turned out not to be RPM.

Signal names marked **opendbc** come from comma.ai's
[`_subaru_preglobal_2015.dbc`](https://github.com/commaai/opendbc/blob/master/opendbc/dbc/generator/subaru/_subaru_preglobal_2015.dbc),
written for the 2015-19 Outback and Legacy, plus the `371` definition from
[`subaru_outback_2015.dbc`](https://github.com/commaai/opendbc/blob/master/opendbc/dbc/generator/subaru/subaru_outback_2015.dbc)
in the same directory. Their IDs match this car's, but several scales and
layouts don't; differences are noted below.

Bytes are numbered from 0, bits from 0 at the least significant end, and
`b5.6` means byte 5 bit 6. "LE" means little-endian. Bit positions written
`16-29` count from bit 0 of byte 0, least significant first, as opendbc does.
"r" is the correlation coefficient against the named PID or quantity.

## Message list

24 IDs besides the `7E0` requests and `7E8` replies, about 880 frames per
second. The drive has the same 24 IDs at the same rates.

| ID | Period | DLC | opendbc name | Contents on this car |
|---|---|---|---|---|
| `002` | 10 ms | 8 | Steering | **Steering angle**; counter, checksum |
| `0D0` | 20 ms | 8 | G_Sensor | **Steering angle**; lateral and longitudinal acceleration; probably yaw rate |
| `0D1` | 20 ms | 4 | Brake_Pedal | **Vehicle speed**; brake pressure |
| `0D2` | 20 ms | 8 | Brake_2 | Constant in both recordings |
| `0D3` | 20 ms | 7 | Brake_Type | Brake light; starter; counter |
| `0D4` | 20 ms | 8 | Wheel_Speeds | **Four wheel speeds** |
| `140` | 10 ms | 8 | Throttle | **Engine RPM, throttle**; pedal; off-throttle flags; counter, parity |
| `141` | 10 ms | 8 | Engine | Engine speed; two load-like fields; gear and flags; parity |
| `144` | 20 ms | 8 | CruiseControl | Brake pedal; parity |
| `152` | 20 ms | 8 | Stalk | Brake light, parking brake; counter, parity |
| `154` | 1 s | 7 | | Brake light and parking brake again |
| `156` | 20 ms | 8 | | Nearly constant |
| `282` | 50 ms | 8 | Dashlights | Driver's seatbelt; outside temperature; counter |
| `360` | 50 ms | 8 | Engine_Temp | **Oil and coolant temperature, manifold pressure**; fuel flow |
| `361` | 50 ms | 8 | | Gear; counter, parity |
| `370` | 50 ms | 8 | Steering_Torque_2 | Steering torque; counter |
| `371` | 20 ms | 8 | Steering_Torque | **Steering angle**; steering torque |
| `372` | 100 ms | 8 | | Counter and parity bit in byte 1 |
| `374` | 1 s | 8 | BodyInfo | Two bits changed once |
| `375` | 1 s | 8 | | Vehicle-stopped flag |
| `376` | 50 ms | 1 | | Constant `FA` |
| `3D1` | 100 ms | 8 | Dash_State2 | Unexplained |
| `660` | 500 ms | 8 | | 0.1 s timer in bytes 6-7 |
| `6FA` | 1 s | 5 | | Byte 0 low nibble is a counter |

Bold signals are confirmed against polled PIDs or against each other; the
rest are inferred from how they behave.

opendbc's `148` (Transmission, with the automatic's gear) and `149`
(CVT_Ratio) are absent, as expected on a manual. So are the EyeSight
messages (`160`-`167`) and `362` (Fuel).

Periods are steady: most IDs' inter-frame gaps stay within about 3% of the
nominal period. `282` and `371` jitter by about 5%, `156` by about 10%, and
the 10 ms engine messages `140` and `141` range 8.5-11.4 ms.

## Confirmed against polled PIDs

Checked against the mode 01 replies recorded alongside, taking the latest
broadcast frame before each reply. Errors include the up to 10-50 ms between
frame and reply, so they grow when a value is changing fast.

| Signal | Encoding | Check (drive unless noted) |
|---|---|---|
| Engine RPM | `140`: bits 16-29 LE (byte 2, plus byte 3 & `3F`), 1 rpm per bit | 0-6,400 rpm, r = 0.9998; median error 2.75 rpm, 95th percentile 30. Idle: within 2.5 rpm in all 120 samples |
| Vehicle speed | `0D1`: bytes 0-1 LE, 0.05625 km/h per bit | 0-139 km/h, slope 0.05625, r = 1.0000; median error 0.24 km/h, max 1.6. PID `0D` is whole km/h: rounded, the broadcast speed equals it in 96% of samples taken at a steady speed, and is 1 off in the rest |
| Wheel speeds | `0D4`: four 16-bit LE values, 0.05625 km/h per bit | Each wheel against PID `0D`: r = 0.9999, slope 0.05618-0.05629. `0D1`'s speed is their mean, within half a km/h in 98% of frames |
| Throttle | `140` byte 6, 0-255 | PID `11`'s raw byte is 29 + byte 6 × 176/255, rounded: 9 at idle (14%), 255 at wide-open throttle (80.4%). Exact in 89% of samples at a steady throttle, and 1 off (0.4%) in the rest |
| Manifold pressure | `360` byte 4, 2 kPa per bit | Slope 2.00, intercept 0, r = 0.999; within 1 kPa of PID `0B` in 91% of samples, and in 96% at a steady pressure |
| Oil temperature | `360` byte 2, minus 40, in °C | 43-91 °C during warm-up; equal to PID `5C` in at least 95% of samples, never more than 1 °C off. Idle: equal in all 120 |
| Coolant temperature | `360` byte 3, minus 40, in °C | 47-96 °C; equal to PID `05` in at least 95%, never more than 1 °C off. Idle: equal in 119 of 120 |

opendbc's wheel speed scale, 0.0592 km/h per bit, reads about 5% high
against both PID `0D` and `0D1`.

`140` byte 0 (opendbc Throttle_Pedal, 0-255) is the accelerator pedal, but
it doesn't map exactly onto PID `5A`. They correlate closely (r = 0.997),
and full pedal (255) is about 45% on PID `5A`, but the relation has a dead
band. With the pedal held steady, PID `5A`'s raw byte is 0 when byte 0 is 0,
and otherwise about 0.38 × byte 0 + 4.7 (14 gives 10, 137 gives 57). That
matches after rounding in only 79% of steady samples, so it isn't decoded.
`140` byte 5 (opendbc Throttle_Combo) equals byte 0 in 93% of frames and is
otherwise 1-6 lower.

Nothing broadcast matches engine load (`04`), MAF (`10`), timing advance
(`0E`), intake air temperature (`0F`), battery voltage (`42`), the fuel trims
(`06`, `07`), or commanded lambda (`44`). Several fields track load closely
(below) without equalling it.

### Decoding

`broadcast-signals` maps engine RPM, vehicle speed, throttle, manifold
pressure, and the two temperatures to the PIDs they match, and
`broadcast-samples` turns a received message into samples shaped like PID
samples, with the CAN ID added:

```clojure
{:pid 0x0C :name :engine-rpm :value 658.0 :unit :rpm :can-id 0x140 :timestamp-us 2181426917}
```

The live and replay sources emit them alongside polled PIDs, so consumers
keyed by name or PID see one value for each. Broadcast RPM and throttle
arrive every 10 ms, speed every 20 ms, and manifold pressure and the
temperatures every 50 ms, so they usually carry the latest value. Each is
decoded onto the PID's own scale: throttle as PID `11`'s percent, to one
decimal place. Speed keeps one decimal place, finer than PID `0D`'s whole
km/h. Manifold pressure comes in 2 kPa steps, coarser than PID `0B`'s 1 kPa.
Over the drive recording, the broadcast value just before each polled one
differs from it by a median of 0.2 km/h, 0 throttle points, and 1 kPa.

Only add a signal to `broadcast-signals` once it matches a polled PID. Not
decoded:

- **`141`'s engine speed (below):** it would report as PID `0C` too, and at
  idle it lags.
- **The pedal:** see above.
- **The four wheel speeds:** no PID reports a single wheel's speed.

The IDs and layouts are this car's. On another vehicle the same IDs may carry
something else, so pass `:broadcast {}` to the sources there.

## Message structure

Confirmed from the frames alone, in all frames of both recordings:

- **`002` checksum:** byte 4 is the sum of the other seven bytes, mod 256. No
  other ID has a sum or XOR checksum byte.
- **Parity bits:** in `140`, `141`, `144`, `152`, `361`, and `372`, every
  frame has an odd number of set bits across all eight data bytes. The bit
  that carries no data and flips to keep that true is `140` b7.7, `141`
  b5.6, `144` b1.0, `152` b0.3, `361` b1.4, and `372` b1.3.
- **Rolling counters:** `002` byte 3 bits 1-3 (3 bits, bit 0 always set);
  `0D3` byte 6 (full byte); `140` byte 1 low nibble; `152` and `282` byte 1
  high nibble; `361` byte 4 low nibble; `370` byte 5 low nibble; `372`
  byte 1 high nibble; `6FA` byte 0 low nibble. Each increments by one per
  frame and wraps, with no breaks in either recording.
- **`660` timer:** bytes 6-7, big-endian, count tenths of a second (10.00
  per second over the drive). The drive's first frame read 255 (25.5 s); the
  idle recording's read 21,575 (36 minutes). Time since ignition on fits
  both, but wasn't checked against a known ignition time.

## Engine

### `141` engine speed

`141` bits 32-44 (byte 4, plus byte 5 & `1F`) are engine speed, 1 rpm per
bit, reaching 6,403 in the drive. opendbc's Engine_RPM is 12 bits and would
wrap above 4,095.

Most of the time the value is `140`'s RPM: equal to the latest `140` frame in
60% of frames, median difference 0. At a warm idle in neutral it is instead
`140`'s RPM through a first-order low-pass filter with a 2.0 s time constant,
`y += 0.005 × (rpm − y)` per 10 ms `140` frame, rounded:

- Idle recording: within 1 rpm of the filtered value in every frame, against
  a median of 2 rpm from the unfiltered one.
- Drive, final 7 s in neutral: 94% of frames within 1 rpm of the filtered
  value, against 4% for the unfiltered one.
- Drive, cold idle in neutral after the start (1,840 rpm): unfiltered.

So something else besides neutral, perhaps a warm engine or the idle speed
controller's mode, switches the filter on. That condition is unknown.

### Load-like fields in `141`

`141` bits 0-14 (opendbc Engine_Torque) and bits 16-27 (Wheel_Torque) both
track engine load: r = 0.94 and 0.96 against PID `04`, ranging 9,372-12,032
and 1,183-3,648. Neither is PID `04` itself, and the car supports no torque
PID (`62`-`64`) to check them against, so their units are unknown. At idle,
byte 2, the low byte of bits 16-27, happened to track timing advance; while
driving it doesn't (r = 0.13).

### Pedal and fuel cut flags

- **`140` b3.6** (opendbc Off_Throttle, bit 30) and **`140` b7.0**
  (Off_Throttle_2, bit 56) are set when the pedal is released. They switch at
  slightly different pedal positions, around 1-5%.
- **`141` b5.7** is set at idle and during most deceleration with the pedal
  released.
- **`141` b6.6** is set during deceleration with the pedal released and the
  clutch out, and clears when the clutch goes in, even with the pedal still
  released. That suggests decel fuel cut, but it agrees with `360` byte 1
  reading 0 in only 83% of frames, so it isn't confirmed.
- **`141` byte 7** takes `00`, `08`, or `10` during deceleration; unexplained.

### `360` byte 1: fuel flow

`360` byte 1 tracks fuel mass flow computed from polled PIDs,
MAF / (14.7 × commanded lambda): r = 0.996 when load is above 0, about
0.185 g/s per bit with an intercept near 0. It reads 0 in most samples where
PID `04` reads 0, which on this car happens during deceleration. The car
doesn't support PID `5E` (fuel rate), so this can't be confirmed directly.
Inference: it may be the fuel consumption the instrument cluster uses for
its fuel economy display.

### Gear

Two messages report the gear. The table gives the median engine rpm per
km/h in each gear, from polled RPM and speed above 15 km/h:

| Gear | rpm per km/h |
|---|---|
| 1 | about 124 (much of the 1st-gear data is clutch slip at launch) |
| 2 | 66.6 |
| 3 | 46.1 |
| 4 | 34.6 |
| 5 | 26.2 |

- **`361` byte 0** is the gear, 1-5, and 0 at a standstill or with no gear
  engaged. It reads 0 throughout the idle recording.
- **`141` b6, bits 0-2** are also the gear, 1-5, but keep reading 1 at a
  standstill in 1st. They read 7 with no gear engaged, including all of the
  idle recording (`141` byte 6 is `27` there), so 7 probably means neutral.
  While shifting, the two messages briefly disagree. Before the engine
  starts, byte 6 is `3F`.
- **`141` b6.5** is set at standstill and low speed, and after some shifts
  for a few seconds. It isn't the clutch pedal: it stays set throughout the
  idle recording, idling in neutral with the clutch out. Unexplained.

PID `65` (auxiliary inputs, supported by this car) reports the gearbox's
neutral switch; polling it would test the "7 is neutral" reading.

### Unexplained engine bytes

- **`360` byte 0** (165-175 at idle) rose and fell with RPM at idle
  (r = 0.80), but not while driving (r = −0.07, at most 0.29 against any
  PID).
- **`144` byte 2** rises with RPM (r = 0.86 while driving) without matching
  it. At idle it mirrored `141` byte 2.
- **`361`:** byte 7 rose from 115 to 157 over the first 6 minutes of the
  drive and then held at 157, but read 51-52 during the warm idle
  recording, so it isn't a temperature. Byte 5's high nibble plus byte 6
  remain a noisy value with no PID match.
- **`3D1` byte 0** varies widely and slowly; no match.
- **`141` bytes 0-1** were a constant `65 26` at idle; they are the load-like
  field above.

## Speed, brakes, and chassis

- **Brake pressure:** `0D1` byte 2 (opendbc Brake_Pedal) is 0 until the
  brake is pressed, then rises to at most 18. While braking it tracks
  deceleration: r = −0.95, about 0.13 m/s² per count.
- **Brake switch:** pressing the brake sets four bits, which agree in 99.9%
  of frames: `0D3` b2.5 (opendbc Brake_Light, bit 21), `152` b6.4
  (Brake_Light, bit 52), `144` b6.3 (Brake_Pedal_On, bit 51), and `154`
  b6.2. In all 11 brake applications `0D3` changed first, `152` 11-36 ms
  later, and `144` 21-54 ms later.
- **Longitudinal acceleration:** `0D0` byte 7 alone, signed, about
  −0.089 m/s² per bit, positive when slowing down. Over 0.5 s windows it
  matches the rate of change of wheel speed with r = −0.96; the offset
  includes road grade. opendbc reads bytes 6-7 together, which doesn't fit:
  byte 6 is a separate signal.
- **Probably yaw rate:** `0D0` byte 6, signed. Over 0.5 s windows it matches
  the yaw rate computed from the rear wheel speed difference with r = 0.83,
  about 0.7°/s per bit. It reads 1-2 at rest, which explains its 1-or-2
  flicker at idle.
- **Lateral acceleration:** `0D0` bytes 2-3, signed LE (byte 3 is only ever
  a sign extension), about 0.072 m/s² per bit. It matches speed × yaw rate
  with r = 0.87 over 0.5 s windows.

The yaw rate here assumes opendbc's wheel order (FL, FR, RL, RR) and a
1.51 m track width. Both are assumptions, so the yaw-rate and lateral scales
are approximate.

## Steering

All three opendbc steering angles are steering angle on this car. Each
follows the path curvature computed from wheel speeds (yaw rate ÷ speed)
with r = 0.98, and they track each other:

- **`002`** bytes 0-1, big-endian, signed, 0.1° per bit: −527° to +69° in the
  drive.
- **`0D0`** bytes 0-1, signed LE: exactly −1 × `002`'s value (slope −1.0003,
  r = −0.9998), offset by 22.8 raw (2.3°).
- **`371`** bytes 5-6, signed LE: 3.68 × `0D0`'s value, so about 0.027° per
  bit rather than opendbc's 0.033°. It reaches 522°, matching the others.

`002`'s value increases when turning left, assuming the wheel order above.
The constant readings at idle (−8.3°, −5.9°, and −10.2° by opendbc's scales)
disagreed only through these offsets and `371`'s scale. Which reading is
zero with the wheel straight is still unknown, because the drive's straight
stretches don't pin it down more closely than a few degrees.

**Steering torque (`370`, `371`):** read with the Outback 2015 layout,
`371`'s Steer_Torque_Sensor (bits 29-39, signed) and Steer_Torque_Output
(bits 16-26, signed) both move with cornering (r = −0.64 and −0.69 against
lateral acceleration), as power steering torque would. At idle `370`
byte 4 equalled `371` byte 4 in 99% of frames; while driving it does in only
12%, so `370` isn't simply a slower copy. `370`'s bits 29-31, always 0 at
idle, do move while driving.

## Body

- **Parking brake:** `152` b6.3 and `154` b6.3 (always equal) are set at
  the start of the drive until 9.9 s, before the car pulled away, and again
  from 551 s, after the final stop. Inference from timing only.
- **Driver's seatbelt:** `282` b5.0 (opendbc SEATBELT_FL, bit 40) is set
  until 37 s and again from 554 s, so set probably means unbuckled. `282`
  b5.1 cleared at 1.5 s. Inference from timing only.
- **Outside temperature:** `282` byte 4 minus 40 looks like outside
  temperature in °F. It read 108 at the start (68 °F, 20 °C) when PID `46`
  read 21 °C, and 90 (50 °F, 10 °C) when PID `46` read 10 °C. `282` byte 3
  follows byte 4 more slowly, like a dashboard display that only updates
  while moving. Inference: no PID reports °F to compare against.
- **Vehicle stopped:** `375` b0.4 sets when wheel speed falls to about
  3 km/h and clears above about 5 km/h, at every stop in the drive.
- **Engine start:** `0D3` b1.6 is set from 4.6 s to 5.7 s, while the starter
  would be cranking. `0D3` byte 3, `152` b0.5, `361` b1.5 and b4.5, and `370`
  b2.5 and b7.7 also change once during the start.

## Still unexplained or untested

- **Doors (`374`):** b3.7 and b4.7, and `375` b3.7, cleared together at
  92.5 s while moving at about 54 km/h. opendbc's door bits (b3.0-b3.4)
  didn't change; b3.0 (DOOR_OPEN_FR) was set throughout the idle recording.
- **Blinkers** (`282` bits 44-45), **headlights and wipers** (`152` byte 7),
  and **cruise control** (`144`): never changed in either recording.
- **`144`:** b3 changed from `23` to `24` at 6.4 s, and b4 from `0B` to `0C`
  at 117 s.
- **`156` byte 7** changed from `23` to `22` once, at 475 s.
- **`3D1` byte 1** flickered between `10` and `0F` around 67-71 s.
- **`0D2`** and **`376`**: constant.

## Next recording

A recording with these done one at a time, and a note of when, would settle
most of what's left:

1. Parked, engine running: the wheel straight ahead, then at full lock each
   way; each door opened and closed; each blinker, the headlights, the high
   beams, and the wipers; the parking brake on and off; the clutch pressed
   and released in neutral; and a few throttle blips.
2. Add PIDs `45` (relative throttle), `49` (pedal position D), `4C`
   (commanded throttle), and `65` (neutral switch) to the polling schedule.
   They would pin down the scales of `140` bytes 0 and 6 and test the gear
   reading of 7.
