# Broadcast CAN traffic

The OBD port carries the car's own CAN traffic at 500 kbps. A pass-all filter
receives it without sending anything, and every [recording](recordings.md)
captures it in `raw.bin`. This page records what that traffic contains on the
project's 2015 Impreza (manual).

It comes from two [reference recordings](recordings.md#reference-recordings), each polling mode 01 PIDs alongside:

- **`2026-10-24-idle`:** 60 seconds of a warm engine idling in neutral, parked.
  Engine speed wanders between 633 and 662 rpm and nothing else changes.
- **`2026-10-28-drive`:** 9 minutes 17 seconds, 561,400 frames. It starts
  with the ignition on and the engine off (coolant 47 °C), the engine starts
  at 5 s, and it covers town and highway driving through all five gears up to
  139 km/h, a full-throttle pull to 6,400 rpm, braking to several stops, and a
  warm idle in neutral with the parking brake on at the end. When the wheel
  was turned, doors opened, or lights switched wasn't noted.

The doors come from a third, `2026-11-09-194644` (61 seconds, parked, engine
on a cold fast idle), in which each door was opened and closed in a noted
order; see [Doors](#doors).

Two drives on 2026-11-09, `2026-11-09-144943` (cold start) and
`2026-11-09-150052` (warm start), are the first without DID polling to have
PIDs `45`, `49`, `4C`, and `65`. Findings marked "2026-11-09" come from
them, checked where it says so against the two DID drives
(`2026-10-29-did-drive` and `2026-10-30-did-drive`) and the recordings
above.

Findings marked "2026-11-10" come from a pass over all seven recordings
that tested what other people have published about related cars (see
below) against this one. It found the clutch, reverse, torque, and several
others, and overturned one earlier reading: the yaw rate and the lateral
acceleration in `0D0` had been taken for each other.

Findings marked "second pass" come from another pass the same day, which
asked what could be worked out from the signals already decoded. It found
the battery current, confirmed the barometer against the road's height,
and checked the scales against the car's published figures; see
[Worked out from the decoded signals](#worked-out-from-the-decoded-signals).

Findings marked "2026-11-11" test a DBC file published for a 2015 WRX,
which lists the CAN messages that car's ECU sends and reads, worked out
from its ROM. The WRX has a different engine and ECU, so each reading was
checked against the seven recordings. It explained `144` byte 2, gave the
barometer its scale, and named several flags; see
[A 2015 WRX's ECU](#a-2015-wrxs-ecu).

Findings marked "BRZ logs" come from the same day: raw CAN logs a 2017 BRZ's
owner published, of drives in which the lights, the cruise control, the
doors, and more were worked on purpose. They are another car's, so they
confirm where to look and test nothing here; see
[A 2017 BRZ's logs](#a-2017-brzs-logs).

Findings marked "2026-11-11-200210" come from a 32 s recording of that
name, made parked that evening: the light switch, the blower, and the air
conditioning were switched on one at a time, in a noted order. It is the
first test here of the light bits and of the WRX's names for the air
conditioning and the radiator fan; see
[Lights, blower, and air conditioning](#lights-blower-and-air-conditioning).

Findings that rest on one recording say which. The drive overturned some idle
findings: at idle almost every varying engine byte moved with RPM, and most
of those turned out not to be RPM.

Signal names marked **opendbc** come from comma.ai's
[`_subaru_preglobal_2015.dbc`](https://github.com/commaai/opendbc/blob/master/opendbc/dbc/generator/subaru/_subaru_preglobal_2015.dbc),
written for the 2015-19 Outback and Legacy, plus the `371` definition from
[`subaru_outback_2015.dbc`](https://github.com/commaai/opendbc/blob/master/opendbc/dbc/generator/subaru/subaru_outback_2015.dbc)
in the same directory. Their IDs match this car's, but several scales and
layouts don't; differences are noted below.

Two more lists were used as hypotheses, and each claim taken from them was
tested here before it was written down:

- **RaceChrono:** the
  [BRZ, 86, and FR-S notes](https://github.com/timurrrr/RaceChronoDiyBleDevice/blob/master/can_db/ft86.md)
  from the RaceChrono DIY project, for the 2013-20 cars, which share this
  bus layout.
- **WRX list:** a
  [list for a 2016 WRX](https://github.com/afreiday/2016-wrx-can-ids). It
  numbers bytes from 1.

The second pass found more lists, used for the
[untested leads](#leads-from-other-cars) only: timurrrr's
[first-generation BRZ notes](https://github.com/timurrrr/ft86/blob/main/can_bus/gen1.md),
a [Forester SJ head-unit adapter](https://github.com/Dem0nis/canbox_subaru_sj/blob/HEAD/cars/subaru_sj.c),
[open-can-db](https://github.com/equinox311/open-can-db), and the 2015 and
2016 Outback files from before opendbc generated them.

Two more came on 2026-11-11, each with its own section below: a DBC file
worked out from a [2015 WRX's ROM](https://github.com/Jengacat/subaru-dit-defs),
and the logs in a [2017 BRZ owner's notes](https://github.com/painellope/BRZ-2017).

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
| `0D0` | 20 ms | 8 | G_Sensor | **Steering angle**; yaw rate; lateral and longitudinal acceleration; an axle comparison updated every 60 m |
| `0D1` | 20 ms | 4 | Brake_Pedal | **Vehicle speed**; brake pressure |
| `0D2` | 20 ms | 8 | Brake_2 | The stability control's torque request and limit to the engine, never used: constant `00 00 FF FF`, but for one pulse of b5.5 |
| `0D3` | 20 ms | 7 | Brake_Type | Brake light; starter; distance counter; counter |
| `0D4` | 20 ms | 8 | Wheel_Speeds | **Four wheel speeds** |
| `140` | 10 ms | 8 | Throttle | **Engine RPM, throttle**; pedal; clutch; A/C compressor; accessory-load and off-throttle flags; counter, parity |
| `141` | 10 ms | 8 | Engine | Engine speed; requested and actual torque; gear and flags; fuel cut; parity |
| `144` | 20 ms | 8 | CruiseControl | Brake pedal; available torque; engine-start and warm-up counters; drive-cycle flag; parity |
| `152` | 20 ms | 8 | Stalk | Brake light, parking brake, reverse; the light switch in byte 7; counter, parity |
| `154` | 1 s | 7 | | Brake light, parking brake, and reverse again; any door open |
| `156` | 20 ms | 8 | | Nearly constant; byte 7 follows fuel level |
| `282` | 50 ms | 8 | Dashlights | Fuel sender; driver's seatbelt; outside temperature; counter |
| `360` | 50 ms | 8 | Engine_Temp | **Oil and coolant temperature, manifold pressure**; fuel flow; A/C request and radiator fan; drive-cycle and steering-load flags |
| `361` | 50 ms | 8 | | Gear; check engine lamp; barometric pressure; engine stopped; battery current and probably temperature; counter, parity |
| `370` | 50 ms | 8 | Steering_Torque_2 | Steering torque, as in `371`; the assist's load on the engine; counter |
| `371` | 20 ms | 8 | Steering_Torque | **Steering angle**; steering torque and assist |
| `372` | 100 ms | 8 | | Counter and parity bit in byte 1 |
| `374` | 1 s, and on change | 8 | BodyInfo | Each door open; b4.7 is b3.7 XOR any door open |
| `375` | 1 s, and on change | 8 | | Vehicle-stopped flag; each door open again; parking lights again; door-close pulse and timer |
| `376` | 50 ms | 1 | | Constant `FA` |
| `3D1` | 100 ms | 8 | Dash_State2 | Outside temperature, finer than `282`'s |
| `660` | 500 ms | 8 | | Ignition cycle counter in bytes 2-3; 0.1 s timer in bytes 4-7 |
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
| Throttle | `140` byte 6, 0-255 | PID `11`'s raw byte is 29 + byte 6 × 176/255, rounded: 9 at idle (14%), 255 at wide-open throttle (80.4%). Exact in 89% of samples at a steady throttle, and 1 off (0.4%) in the rest. 2026-11-09: PID `45` (relative throttle) is the same without the 29: its raw byte is byte 6 × 176/255 (fitted slope 0.690 and 0.689, intercept 0), exact in 86% and 89% of steady samples in the two drives and 1 off in the rest |
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

2026-11-09: the two later drives fit a simpler line, which disagrees with
the one above. With the pedal held steady, PID `5A`'s raw byte is about
0.455 × byte 0 with no offset (r = 0.999 and 0.998; full pedal, 255, is 116
or 117, 45.5-45.9%), and PID `49` (pedal position D) is the same plus about
34.5 raw (13.5%). Neither is exact: with the pedal pressed, the fitted line
matches PID `5A` after rounding in only 30% and 48% of steady samples, and
is within 1 count in about 95% of all of them. The pedal still isn't
decoded. Why the first drive fitted a shallower line with a dead band isn't
known.

2026-11-10: byte 0 is the same byte as DID `10A6`, which
[`uds/did-signals`](subaru-ssm.md) fits to PID `5A` as 0.178 × x percent
(0.455 ÷ 2.55, the line above). In the two DID drives it equalled the DID
reply in 92.5% and 96.9% of 741 replies and was within 2 counts in all but
one. So it is the ECU's own pedal value, and PID `5A` is derived from it
inexactly. `inferred-signals` decodes it on its own scale, as
`:pedal-travel`, 0-100% of the byte's range.

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

2026-11-12: five of the six are marked `:replaces-poll? true` in
`broadcast-signals`, and the live source no longer polls their PIDs (`0C`,
`0D`, `11`, `05`, `5C`) while it decodes them: `can/poll-schedule` leaves
out what `broadcast/replaced-pids` gives. That takes the fast PIDs from 13
to 10 and the slow from 14 to 12, so the rest come round sooner. Manifold
pressure isn't marked, being coarser than PID `0B`. Mark a signal only if
it is as fine as its PID and the check above shows it equal. `record` still
polls every PID, so a recording made with it can repeat these checks; one
made from the dashboard can't.

Only add a signal to `broadcast-signals` once it matches a polled PID. Not
decoded:

- **`141`'s engine speed (below):** it would report as PID `0C` too, and at
  idle it lags.
- **The pedal:** see above. It is an inferred signal, on its own scale.
- **The four wheel speeds:** no PID reports a single wheel's speed.

The IDs and layouts are this car's. On another vehicle the same IDs may carry
something else, so pass `:broadcast {}` and `:signals {}` to the sources
there.

### Inferred signals

`inferred-signals` holds signals identified below by how they behave but
matched to no PID, so their scales and directions aren't confirmed.
`signal-samples` decodes them into `{:signal :value :unit :can-id
:timestamp-us}` maps, and the live and replay sources emit them as
`{:type :signal ...}` events (`:signals {}` turns them off). The dashboard
shows them tagged inferred. Each entry also carries how to show it (`:label`,
`:group`, and where they apply `:format`, `:raw?`, `:parts`, and
`:override`), which
`catalog/catalog` passes to the dashboard, so a signal added here appears
there with no other change.

The catalog also gives the bits of the frame each signal here and in
`broadcast-signals` is decoded from, as `:bits`, a mask per byte: `[0 0 0xFF
0x3F]` for RPM. `decode-bits` finds them by flipping each bit of a frame
and seeing whether the decoded value changes, so they follow the decoding.
The dashboard's Bus tab colours those bits.

| Signal | Encoding | Value |
|---|---|---|
| `:steering-angle` | `002` bytes 0-1, big-endian, signed, 0.1° per bit | Degrees, positive for a left turn |
| `:yaw-rate` | `0D0` bytes 2-3, signed LE, about 0.2865°/s (0.005 rad/s) per bit | Degrees per second, positive to the left |
| `:lateral-acceleration` | `0D0` byte 6, signed, about 0.0204 g (0.2 m/s²) per bit | g, positive to the left; reads 0.02-0.04 at rest |
| `:longitudinal-acceleration` | `0D0` byte 7, signed, about −0.0102 g (0.1 m/s²) per bit | g, positive when speeding up |
| `:brake-pressure` | `0D1` byte 2 | Raw count, 0-18 |
| `:brake` | `0D3` b2.5 | Boolean |
| `:wheel-speeds` | `0D4`, four 16-bit LE values, 0.05625 km/h per bit | Map of `:front-left`, `:front-right`, `:rear-left`, `:rear-right` in km/h, in opendbc's order |
| `:pedal-travel` | `140` byte 0 ÷ 2.55 | Percent of the byte's range; full pedal is about 45% on PID `5A` |
| `:clutch` | `140` b1.7 | Boolean, true while the clutch pedal is pressed |
| `:ac-compressor` | `140` b1.6 | Boolean, true while the air conditioning's compressor is engaged |
| `:accessory-load` | `140` b7.5 | Boolean; it set when the blower was switched on; see [Lights, blower, and air conditioning](#lights-blower-and-air-conditioning) |
| `:gear` | `141` b6 bits 0-2 | 1-5, or `:neutral` for 7; reverse reads 1, and a display shows R from `:reverse` |
| `:requested-torque` | `141` bits 0-14 LE, minus 10000, 0.1 Nm per bit | Newton metres, what the pedal asks for |
| `:engine-torque` | `141` bytes 2-3 LE, minus 10000, 0.1 Nm per bit | Newton metres, negative on the overrun |
| `:fuel-cut` | `141` b7.3 | Boolean |
| `:available-torque` | `144` byte 2, 1.6 Nm per bit | Newton metres, the most the engine can make at its present speed; see [A 2015 WRX's ECU](#a-2015-wrxs-ecu) |
| `:engine-starts` | `144` byte 3 | Raw count, 0-255; see [Counters and fuel level](#counters-and-fuel-level) |
| `:warm-up-cycles` | `144` byte 4 | Raw count, 0-255 |
| `:parking-brake` | `152` b6.3 | Boolean |
| `:reverse` | `152` b6.2 | Boolean |
| `:parking-lights` | `152` b7.2 | Boolean, true with the light switch in either position |
| `:headlights` | `152` b7.3 | Boolean, true with the light switch in its second position |
| `:fuel-gauge` | `156` byte 7 | Raw count, about 2.4% of the tank each: 35 at 97% and 15 at 51% |
| `:fuel-sender` | `282` bits 0-11 LE | Raw count that rises as the tank empties, about 8 for each 1%: 97 at 98% and 483 at 51% |
| `:driver-seatbelt` | `282` b5.0 | `:buckled` or `:unbuckled` |
| `:fuel-flow` | `360` bytes 0-1 LE, about 0.00072 g/s per bit | Grams per second |
| `:radiator-fan` | `360` b6.4 | Boolean; seen only with the air conditioning on |
| `:ac-request` | `360` b6.7 | Boolean, true while the air conditioning is switched on |
| `:check-engine-lamp` | `361` b1 bits 5-7 | `:on` for 0, `:off` for 1, and `:flashing` for 4, which is the WRX's reading and hasn't been seen here |
| `:barometer` | `361` byte 3, 0.4 kPa per bit, plus 13.2 | kPa |
| `:engine-stopped` | `361` b4.5 | Boolean, true while the engine isn't turning |
| `:battery-current` | `361` byte 5 high nibble plus byte 6, 12 bits LE, minus 2048 | Raw count, positive while the battery charges; see [Battery current and temperature](#battery-current-and-temperature-in-361) |
| `:door-front-left` | `374` b3.0 | Boolean, true while the driver's door is open |
| `:door-front-right` | `374` b3.1 | Boolean, true while the front passenger door is open |
| `:door-rear-right` | `374` b3.2 | Boolean, true while the rear passenger-side door is open |
| `:door-rear-left` | `374` b3.3 | Boolean, true while the rear driver-side door is open |
| `:outside-temp` | `3D1` bits 0-14 LE, minus 3600, 0.2 °F per bit above −40 °F | °C |

The left/right directions assume opendbc's wheel order. Move a signal to
`broadcast-signals` only if it comes to match a polled PID.

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
  both, but wasn't checked against a known ignition time. Bytes 4-5 were 0
  throughout, so the timer is probably bytes 4-7. Bytes 2-3 are a
  [counter](#counters-and-fuel-level).

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
controller's mode, switches the filter on.

2026-11-10: the filter needs the car stopped, the pedal released, and a
warm engine. Taking each moment in the five drives when the filtered and
unfiltered values were at least 25 rpm apart, `141` was nearer the filtered
one in 1,144 samples, and in 1,128 of those `375` b0.4 (vehicle stopped)
was set, `140` byte 0 read 0, and coolant was at least 70 °C. The clutch
and the gear don't matter. Those three aren't enough, though: in 1,319
samples with all three, `141` was unfiltered, so the filter probably comes
on a while after the stop. That delay wasn't measured.

### Torque in `141`

`141` bits 0-14 (opendbc Engine_Torque) and bits 16-27 (Wheel_Torque) both
track engine load: r = 0.94 and 0.96 against PID `04`, ranging 9,372-12,032
and 1,183-3,648. Neither is PID `04` itself, and the car supports no torque
PID (`62`-`64`) to check them against. At idle, byte 2, the low byte of
bits 16-27, happened to track timing advance; while driving it doesn't
(r = 0.13).

2026-11-10: both are engine torque in tenths of a newton metre, offset by
10000. The second is bytes 2-3 whole: its top four bits always read 2,
which is just where 9,375-11,850 falls, so both fields are 16-bit values
around 10000 on one scale. Read as (raw − 10000) × 0.1 Nm:

- **The peak fits the engine.** Over the five drives the first field
  reaches 203 Nm and the second 185 Nm, both at 4,000-4,500 rpm. The FB20
  is rated at 196 Nm at 4,200 rpm.
- **The floor is the engine's drag.** With the pedal released both fields
  read the same negative torque, which depends only on engine speed: −17 Nm
  up to 1,000 rpm, −22 at 2,000, −30 at 3,000, −41 at 4,000, −53 at 5,000,
  and −63 at 6,000.
- **The car's mass comes out right.** In gear with the clutch out and the
  brake off, torque × engine speed was fitted to mass × acceleration ×
  speed plus drag, over 1 s windows (161-393 per drive). The mass came out
  at 1,560-1,710 kg from either field in all five drives (r = 0.93-0.97).
  The car weighs about 1,350 kg empty; a driver, the inertia of the wheels
  and engine, and drivetrain loss make up the rest.

The first field is what the pedal asks for and the second what the engine
delivers. The first follows the pedal (r = 0.92-0.94 against DID `10A6`)
and the second the air per revolution (r = 0.98 against DID `10A3`). At a
warm idle in neutral the first reads −17.1 Nm, the drag at that speed, and
the second +4.5 Nm; on the cold fast idle, −17.4 and +9.0. Which is
"requested" and which "actual" is an inference from that, and so are the
names `:requested-torque` and `:engine-torque` in `inferred-signals`.

### Pedal and fuel cut flags

- **`140` b3.6** (opendbc Off_Throttle, bit 30) and **`140` b7.0**
  (Off_Throttle_2, bit 56) are set when the pedal is released. They switch at
  slightly different pedal positions, around 1-5%.
- **`141` b5.7** is set at idle and during most deceleration with the pedal
  released. 2026-11-10: it means the pedal is released. It equals "`140`
  byte 0 is 0" in 99.8% of the 282,381 frames in the five drives, and was
  never set with the pedal pressed.
- **`141` b6.6** is set during deceleration with the pedal released and the
  clutch out, and clears when the clutch goes in, even with the pedal still
  released. That suggests decel fuel cut, but it agrees with `360` byte 1
  reading 0 in only 83% of frames, so it isn't confirmed. 2026-11-09: with
  the bit set, PID `04` read 0 in 92% of samples in each drive (451 of 491
  and 711 of 777), and PID `03` read "open loop due to load or deceleration"
  in 31 of 40 and 40 of 54. That fits fuel cut but doesn't prove it: load
  also read 0 in 170 and 183 samples with the bit clear. `360` byte 1 is a
  poor check, because it also reads 0 at idle, where the fuel flow is below
  one count.
- **`141` byte 7** takes `00`, `08`, or `10` during deceleration.
  2026-11-10: **b7.3 is fuel cut.** With byte 7 at `08`, the 16-bit fuel
  flow in `360` (below) read exactly 0 in 42,720 of 42,861 frames (99.7%).
  When the pedal is released at speed, byte 7 first reads `10` for
  0.2-0.45 s, during which the flow falls to 0 (it read 0 in 54% of those
  frames), and then `08` until fuel comes back. So b7.4 marks the cut
  starting. b6.6 above is wider than either: it was set in 21,335 frames
  with byte 7 at `00` and fuel flowing. `inferred-signals` decodes b7.3 as
  `:fuel-cut`.

### `360` bytes 0-1: fuel flow

`360` byte 1 tracks fuel mass flow computed from polled PIDs,
MAF / (14.7 × commanded lambda): r = 0.996 when load is above 0, about
0.185 g/s per bit with an intercept near 0. It reads 0 in most samples where
PID `04` reads 0, which on this car happens during deceleration. The car
doesn't support PID `5E` (fuel rate), so this can't be confirmed directly.
Inference: it may be the fuel consumption the instrument cluster uses for
its fuel economy display.

2026-11-10: byte 0 is the low byte of the same value, so bytes 0-1,
little-endian, are fuel flow 256 times as fine. Byte 0 alone looked random
while driving because it wraps.

- **Scale:** 0.00069-0.00073 g/s per bit against the same computed flow,
  r = 0.992-0.997 in each of the five drives, with an intercept near 0.
  That is about one cubic millimetre of petrol a second. `:fuel-flow` uses
  0.00072.
- **Idle:** it now reads at idle, where byte 1 was 0: 165-175 at a warm
  idle (0.12 g/s), and 330-535 on the cold fast idle, where it follows the
  computed flow with r = 0.985.
- **Over a drive:** summed, it gives 0.25-0.83 litres for the five drives,
  or 8.4-11.3 l/100 km, the most for the cold start on 2026-11-09.

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
  idle recording (`141` byte 6 is `27` there), so 7 means neutral (checked
  against PID `65` below). While shifting, the two messages briefly
  disagree. Before the engine starts, byte 6 is `3F`.
- **`141` b6.5** is set at standstill and low speed, and after some shifts
  for a few seconds. It isn't the clutch pedal: it stays set throughout the
  idle recording, idling in neutral with the clutch out. 2026-11-09: it is
  mostly a low-speed flag. In the two drives it was set in all but 49 of
  37,203 frames below 15 km/h and clear in 96% of the 74,001 frames at or
  above it. What sets it after a shift is unexplained.

**7 is neutral.** PID `65` (auxiliary inputs, supported by this car)
reports whether the manual gearbox is in gear. In the four drives that
polled it (the two DID drives and the two on 2026-11-09), every one of the
1,786 PID `65` replies that came while `141`'s gear read 7 said "not in
gear". The reverse doesn't hold: PID `65` also says "not in gear" with a
gear selected while the clutch is pressed or slipping, such as stopped in
1st and pulling away. At a shift it says "not in gear" about 0.1-0.2 s
before `141` reads 7, and "in gear" about 1.5-2 s after `141` reads the
new gear. So PID `65` isn't a copy of this field, and no single broadcast
bit matches it: the closest, `141` b6.5 inverted, agrees in 87% and 90% of
replies. Two bits together do; see the clutch, next.

### Clutch and reverse

2026-11-10. Both were found from other people's lists and then tested
here.

**`140` b1.7 is the clutch switch.** The RaceChrono notes name it. PID `65`
says "in gear" exactly when this bit is clear and `141`'s gear isn't 7: in
99.7%, 99.85%, 99.96%, and 99.75% of the 8,061 PID `65` replies in the four
drives that polled it. The few misses are at changes. The bit is clear in
every frame of both parked recordings, when the pedal wasn't touched, and
it changes a median 0.12-0.26 s before the next PID `65` reply shows it,
which is about the wait for that reply. It is a switch, not a position: it
doesn't say how far the pedal is down, and it is set through most of
pulling away. `inferred-signals` decodes it as `:clutch`.

**`152` b6.2 is reverse.** The wheel speeds have no sign, but the yaw
sensor does. Going forwards, the yaw rate in `0D0` has the same sign as the
yaw rate worked out from the rear wheels in 4,386 of 4,395 samples taken
while turning at 7-54 km/h. With this bit set it has the opposite sign in
all 2,364. The steering angle gives the same answer. The bit was set eight
times in the five drives, for 7-22 s each: at the end of every drive,
parking, and in `2026-10-30-did-drive` also backing out at the start and
once mid-drive. `154` b6.1 follows it. In reverse `141`'s gear reads 1 (in
all but 11 of 11,676 frames) and `361`'s reads 0 or 1.
`inferred-signals` decodes the bit as `:reverse`. `:gear` still decodes to
1, since the two are in different messages, but its entry carries
`:override {:signal :reverse :text "R"}`, and the dashboard's gear tile
shows R while `:reverse` is true.

### Unexplained engine bytes

- **`360` byte 0** (165-175 at idle) rose and fell with RPM at idle
  (r = 0.80), but not while driving (r = −0.07, at most 0.29 against any
  PID). 2026-11-10: it is the low byte of the
  [fuel flow](#360-bytes-0-1-fuel-flow).
- **`144` byte 2** rises with RPM (r = 0.86 while driving) without matching
  it. At idle it mirrored `141` byte 2. 2026-11-10: it depends on engine
  speed alone. With the median for each 100 rpm taken away, what is left
  stays within −1 to +2 counts in 90% of frames. It reads 57 with the
  engine stopped, 60-63 at a warm idle, 80 at 1,000-1,500 rpm, 96 at 2,000,
  106 at 3,000, 114 at 4,000, peaks at 115 around 4,500, and falls to 105
  by 6,000 (the spread was measured in `2026-10-30-did-drive`). That is the
  shape of a full-load torque curve, at a little over half the newton
  metres the [torque](#torque-in-141) fields reach. What it is for is
  unknown. 2026-11-11: it is the torque the engine can make at that
  speed, at 1.6 Nm per count; see [A 2015 WRX's ECU](#a-2015-wrxs-ecu).
- **`361`:** byte 7 rose from 115 to 157 over the first 6 minutes of the
  drive and then held at 157, but read 81-82 during the warm idle
  recording, so it isn't a temperature. In the later recordings it stayed
  within 74-127, 122-137, 139-145, 129-145, and 128-130, with no match.
  2026-11-10: it rises while driving and falls slowly at idle, and it
  started a cold drive at 139, so it carries over. It isn't coolant,
  catalyst temperature, or an average fuel economy (a fit of distance over
  fuel doesn't hold). Byte 5's high nibble plus byte 6, as 12 bits, are
  noise centred on 2048 (mean 2,034-2,058, standard deviation 73-122),
  which follows nothing else on the bus. Second pass: the 12 bits are the
  battery current and byte 7 is probably the battery's temperature; see
  [Battery current and temperature](#battery-current-and-temperature-in-361).
- **`361` byte 3 is probably barometric pressure.** 2026-11-10. It read
  222, 221, 218, 218, 216, 215, and 216 in the seven recordings, moving by
  a count or two within a drive. In the two 2026-11-09 drives it follows
  PID `33` (r = 0.83 and 0.81), stepping down a count when the PID stepped
  from 100 to 99 kPa and back up with it. PID `33` read 100-101 kPa at 218
  and 99-100 at 215-216, so a count is about half a kilopascal. The first
  two recordings didn't poll PID `33`. Second pass: it falls as the road
  climbs. Against the height worked out from the
  [road's grade](#worked-out-from-the-decoded-signals), smoothed over 10 s,
  it fell a count for every 27-61 m climbed in the four drives that covered
  25 m or more of height (r² 0.14-0.54; the byte only moved 2-3 counts).
  Air pressure falls about 0.12 kPa for each 10 m, so 40 m is half a
  kilopascal, the same scale PID `33` gave. 2026-11-11: the WRX's ECU
  sends 0.4 kPa per count plus 13.2, and that is what is decoded; see
  [A 2015 WRX's ECU](#a-2015-wrxs-ecu).
- **`3D1` byte 0** varies widely and slowly; no match. 2026-11-10: it is
  the [outside temperature](#outside-temperature-in-3d1).
- **`141` bytes 0-1** were a constant `65 26` at idle; they are the
  requested torque above.

### Battery current and temperature in `361`

Second pass. **Byte 5's high nibble plus byte 6, 12 bits little-endian, are
the battery current sensor,** centred on 2048. What looked like noise is
the current's ripple; underneath it the value does what a battery's current
does:

| When | Value | Recording |
|---|---|---|
| Ignition on, engine off | 1,719-1,743, steady | `2026-10-28-drive`, to 4.5 s |
| Starter cranking, ECU voltage 8.2 V | Pinned at 250 | `2026-10-28-drive`, 4.5-5.3 s |
| The 15 s after the start | 2,100-2,700, falling | `2026-10-28-drive` |
| Driving with the alternator at full duty | About 2,040 | All five drives |
| Alternator duty (DID `10B2`) cut to about 30 | 1,760-1,900 within a second or two, then creeping up | `2026-10-30-did-drive`, each time |
| Duty restored | About 2,250, falling back to 2,040 over several seconds | `2026-10-30-did-drive` |

Over `2026-10-30-did-drive`, averaged over 1 s, it read 169 below 2,048
with `10B2` under 40 (85 replies), 60 above with it at 40-59 (85), and 4
above with it at 60 or more (383). Within each of those it rises with ECU
voltage, by 0.0016-0.0026 V per count (r 0.42-0.69). So above 2,048 the
battery is charging and below it the battery is supplying the car. In the
three drives where the voltage stayed above 13 V, its 2 s average hardly
moved: a standard deviation of 12-17 counts, against 77-82 in the other
two.

The scale isn't known. Inference: the ECU's own DID for battery current,
`1135` in the lists [`subaru-ssm.md`](subaru-ssm.md#dids-past-10ff) names,
is one byte around 128 in amps, and 12 bits around 2,048 would be that 16
times as fine, a sixteenth of an amp per count. That would make the
ignition-on draw 20 A and the sensor's floor 112 A, both believable, but
`1135` hasn't been read on this car. 250 looks like the sensor's lowest
output, not a current. `inferred-signals` decodes the field as
`:battery-current`, the count less 2,048, with no unit.

2026-11-11: the low nibble of byte 5 is always 0, so bytes 5-6 are one
16-bit word that is always a multiple of 16. The WRX sends its battery
current, from a sensor on a LIN bus, in `362` at 0.004 A per count of a
16-bit word, less 125 A. This car has no `362`. A sixteenth of an amp for
each of the 12 bits is 0.0039 A per count of the word, so the scale guessed
above agrees with the WRX's, but the WRX's ROM keeps what would go in
`361` bytes 5-6 in a different variable (next to the current) and sends
zeros there, so that is support and not a test. The zero points differ: the
WRX's would put it at 31,250 on the word, where this car's readings centre
on 32,768.

BRZ logs: the BRZ sends the same field, with the same low nibble of 0. It
read a median 2,078 while driving (standard deviation 87), sat at 257-258
for 0.6 s as the starter cranked, and ran at 2,200-2,700 for the seconds
after the start, which is what this car's does. Its byte 7 fell from 124 to
122 over a seven-minute drive. So the field belongs to this family of
ECUs, and the BRZ's is nearer this car's than the WRX's is. It gives no
scale either.

**Byte 7 is probably the same sensor's temperature,** on a scale that
falls as it warms. It follows intake air temperature (PID `0F`) across
the seven recordings: r = −0.94 as it stands and −0.97 against the PID
lagged by 40 s, at about −2.1 counts per °C (81 at 50 °C, 158 at
12 °C), with 4 counts left unexplained. So it is a temperature under the
bonnet that moves more slowly than the intake air: it falls at idle, as
the engine bay warms, and rises on the move. That it is the battery's is
an inference from where it sits, next to the current. It isn't decoded.

### Drive-cycle flag

2026-11-10. `360` b5.7 and `144` b1.1 set together, once, in two of the
drives, and stayed set to the end. Inference: they mark the conditions
OBD-II calls the general denominator, which a drive meets once the engine
has run for 600 s, the car has spent 300 s at 25 mph (40 km/h) or more, and
it has idled for 30 s on end.

| Recording | Flag | Since ignition on | At 40.2 km/h or more | First 30 s idle |
|---|---|---|---|---|
| `2026-10-30-did-drive` | Set at 665.7 s | 692 s | 302.6 s when it set | 470 s |
| `2026-11-09-150052` | Set at 572.5 s | 600.6 s when it set | 300 s reached at 505 s | 92 s |
| `2026-10-28-drive` | Never | 581 s at the end | 383 s | None |
| `2026-11-09-144943` | Never | 587 s at the end | 250 s | 407 s |
| `2026-10-29-did-drive` | Never | 953 s at the end | 146 s in the recording | None |

"Since ignition on" is from [`660`'s timer](#message-structure), and the
time at speed is counted from `0D1`. In the first drive the flag set while
accelerating through 50 km/h, with nothing else happening, 2.6 s of
time-at-speed after the count passed 300 s. Counting from 40.5 km/h
instead, it set 0.9 s after, and the second drive still reaches 300 s (at
505 s, with half a second to spare over the whole drive). So the ECU's
threshold is probably a little above 40.2 km/h on this speed signal. In
the second drive the last condition met was the 600 s. In the three drives
where the flag never set, at least one condition was never met; what
happened in the 11 minutes before `2026-10-29-did-drive` began isn't
known. Two events is not many, and the idle condition was never the last
one met, so that part is untested.

2026-11-11: the WRX's ECU sets `144` b1.1 once three of its counters reach
600, 300, and 30, the same three figures. That ROM has `360` b5.7 as an
unidentified flag from another module.

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
  byte 6 is a separate signal. Second pass: the scale is nearer
  0.1 m/s² per bit. Fitted either way round against the rate of change of
  `0D1`'s speed, over 0.5 s and 1 s windows, it came out at 0.092-0.102
  in each of the five drives and 0.094-0.099 with them pooled (r = −0.974),
  and at 0.091-0.110 within bands of hard braking and acceleration. The
  RaceChrono notes give 0.1 m/s², and the body's pitch under braking adds a
  few percent to what the sensor reads, so `inferred-signals` now uses
  0.1 m/s² (0.0102 g) per bit.
- **Yaw rate:** `0D0` bytes 2-3, signed LE (byte 3 is only ever a sign
  extension), about 0.27°/s per bit (0.26-0.33 across speeds and drives),
  positive to the left.
- **Lateral acceleration:** `0D0` byte 6, signed, about 0.2 m/s² per bit
  (0.18-0.26), positive to the left. It reads 1-2 at rest, which explains its 1-or-2
  flicker at idle.
- **Distance:** `0D3` byte 4's top six bits count up as the car moves and
  wrap, and its low two bits are always 0. 2026-11-10: a count is 0.1959 m
  in all five drives, against `0D1`'s speed summed over each drive
  (2.9-9.1 km), and over 2 s windows the two agree with r of at least
  0.99997. At most 4 counts pass between frames. opendbc calls the byte
  Speed_Counter.

**These two were first read the other way round,** with bytes 2-3 as
lateral acceleration and byte 6 as yaw rate. While driving the two
quantities rise and fall together, since lateral acceleration is speed ×
yaw rate, and the first drive alone couldn't separate them (r = 0.87 and
0.83). 2026-11-10: what separates them is speed. Over 0.5 s windows above
7 km/h in the five drives:

| | Per rad/s of yaw rate, at 7-29, 29-58, and over 58 km/h | Per m/s² of speed × yaw rate, in the same bands |
|---|---|---|
| Bytes 2-3 | 172-213, 210-219, 194-214 | 35-39, 17-24, 8-10 |
| Byte 6 | 25-29, 44-59, 82-124 | 4.8-5.6, 4.7-5.4, 3.8-4.6 |

Bytes 2-3 keep one scale against yaw rate at every speed and byte 6 keeps
one against lateral acceleration, and neither does against the other.
Bytes 2-3 match the yaw rate from the rear wheels with r = 0.992 and 0.998
in two drives (0.84-0.93 in the other three). The RaceChrono notes give
the same layout, with 0.005 rad/s (0.2865°/s) and 0.2 m/s² per bit, and
`inferred-signals` uses those: they are round numbers inside the range
measured here. opendbc's Lateral is these bytes 2-3, so its name doesn't
fit this car.

The yaw rate from the wheels assumes opendbc's wheel order (FL, FR, RL, RR)
and a 1.51 m track width. Both are assumptions, so the measured scales are
approximate.

Second pass: **the wheel order is right, and the yaw scale is in doubt by
about 7%.** The first two values in `0D4` are the front wheels: in a turn
their mean runs ahead of the other two's by an amount that grows with the
square of the curvature, as the front axle's wider path does (r² 0.83-0.95
in the five drives). The difference between the rear wheels' speeds,
though, is 1.40-1.42 m times the yaw rate at 0.005 rad/s per bit, in every
drive (r² 0.98-0.996), and the rear track is 1.515 m. Over the 39 turns of
more than 45°, the sensor's angle was 1.07 times the wheels' at the
median. The lateral sensor reads low against speed × yaw rate too, at
0.87-0.93 of it. All of that fits a yaw scale near 0.0047 rad/s per bit.
Against it, turns at junctions should bunch around a right angle, and the
eleven nearest one came to 85-97° by the sensor (90.5° on average) and
77-93° by the wheels. That is weak, since it picks the turns by the scale
being tested. The steering ratio the scale gives is a believable one too
(see [below](#worked-out-from-the-decoded-signals)). The wheels alone can't
settle it: fitted for a track width without the yaw sensor they give
1.58-1.68 m. A full circle driven in a car park would. `:yaw-rate` keeps
0.005 rad/s until then.

**`0D0` bytes 4-5** hold a value that changes once every 60 m travelled
(2026-11-10: 536 changes, 60.0-60.7 m apart in 80% of them) and reads 0
until the car has moved. Its low 10 bits, signed, range −78 to 17, and its
top 6 bits 1 to 18. Neither follows the yaw rate, the accelerations, the
steering angle, or the differences between the wheel speeds (r under 0.5);
both tend to be larger at speed (r = 0.53). Unexplained.

Second pass: **it compares the axles over each 60 m.** The low field is
byte 4 as a signed byte (byte 5's low two bits only extend its sign), and
the other is byte 5 shifted right by two. Taken over the 60 m before each
change, not at one moment, byte 4 follows how much further the front
wheels ran than the rear: r = −0.76 against the front-to-rear speed ratio
in 512 windows, about −2.3 counts for each 0.1% the fronts ran ahead, plus
about 0.25 counts for each m/s of speed (r² 0.68 together). The fronts run
ahead in turns, which is where it goes negative. The second field is tied
to the first: about 2 when byte 4 is negative, and a count or two above
byte 4 when it is positive (r² 0.91 for a fit to byte 4 and its size). Inference: the brake controller is watching for
tyres of different sizes front and rear, which matters with all-wheel
drive. The exact sum isn't pinned down, so neither field is decoded.

BRZ logs: a rear-wheel-drive BRZ sends the same two fields, updated every
60 m (59-61 m in 80% of 77 updates), with byte 5's low two bits extending
byte 4's sign. So whatever the brake controller is watching for, it isn't
particular to all-wheel drive.

**`0D2` b5.5** was set once in the seven recordings, in
`2026-11-09-144943` from 438.5 s to 439.5 s: it set as the brake was
released at a standstill in 1st with the clutch down, and cleared a second
later as the car began to move. That is how hill-start assist would look,
which is a guess from one event; the longitudinal sensor read about
0.3 m/s² at that stop, a slight slope.

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
stretches don't pin it down more closely than a few degrees. The later
drives don't either: above 80 km/h, `002`'s most common reading was −4.0°,
−3.0°, and −2.0° in three drives, and in the fourth (`2026-11-09-150052`,
with little time at that speed) it had no clear peak.

Second pass: **straight ahead is about −2° on `002`.** Fitting the angle
to the path's curvature (yaw rate ÷ speed) and the lateral acceleration,
at 18-90 km/h with the brake off, puts zero curvature at −2.3°, −1.8°,
−2.4°, −1.8°, and −1.9° in the five drives (r² 0.993-0.996). The median
reading with the yaw rate under 0.003 rad/s above 54 km/h agrees: −2.4°,
−2.0°, −2.8°, −1.9°, and −1.3°. `:steering-angle` doesn't take it off.

**Steering torque (`370`, `371`):** read with the Outback 2015 layout,
`371`'s Steer_Torque_Sensor (bits 29-39, signed) and Steer_Torque_Output
(bits 16-26, signed) both move with cornering (r = −0.64 and −0.69 against
lateral acceleration), as power steering torque would. At idle `370`
byte 4 equalled `371` byte 4 in 99% of frames; while driving it does in only
12%, so `370` isn't simply a slower copy. `370`'s bits 29-31, always 0 at
idle, do move while driving.

2026-11-10, in the five drives:

- **`371` bits 0-9** (opendbc Steering_Motor_Flat) are the size of
  Steer_Torque_Output, without its sign: r = 0.992-0.999. So they are how
  hard the motor is assisting.
- **`370` bits 29-39** are `371`'s Steer_Torque_Sensor, sent every 50 ms
  instead of every 20: r = 0.997-0.999 against the latest `371` frame. The
  bytes seldom match exactly while driving because the two frames are sent
  at different moments.
- **`370` byte 0** is the assist again at about half scale (0.47-0.53 ×
  `371` bits 0-9, r = 0.86-0.91), up to 255.
- **`370` b1.0 and `360` b6.1** set together, mostly within 50 ms of each
  other, when the assist is high: `371` bits 0-9 were at least 227
  whenever `370` b1.0 was set. That happened only in the three latest
  drives, at a standstill or walking pace while parking, for up to a
  couple of seconds each time. Inference:
  the steering tells the engine about its load, and the engine answers,
  perhaps to hold the idle up. 2026-11-11: the WRX's ECU reads `370`
  bytes 0-1 as one 16-bit word, adds idle air for it, and
  sets `360` b6.1 while it is above a threshold. Here too it is one word:
  it reaches 527, so b1.0 is its bit 8, not a flag, and b1.1, seen once,
  its bit 9. `360` b6.1 was set with the word as low as 133 and clear with
  it as high as 297, which is a threshold near 256 with some hysteresis
  and the delay between the two frames.

The units of all of these are unknown, so none is decoded.

## Body

- **Parking brake:** `152` b6.3 and `154` b6.3 (always equal) are set at
  the start of the drive until 9.9 s, before the car pulled away, and again
  from 551 s, after the final stop. Inference from timing only.
- **Driver's seatbelt:** `282` b5.0 (opendbc SEATBELT_FL, bit 40) is set
  until 1.5 s, before the engine started, and again from 554 s, after the
  final stop, so set probably means unbuckled. Inference from timing only.
- **`282` b5.1** cleared at 37 s, while moving. It does that once in each
  drive that starts with it set: at 22.8 s, 39.1 s, and 29.4 s in the next
  three, at 15-42 km/h, and it doesn't set again. It was already clear
  throughout `2026-11-09-150052`, which began two minutes after the drive
  before it ended, and stayed set in both parked recordings. Unexplained.
  Second pass: it clears on time spent moving, not on speed or distance.
  The wheels had been turning for 22.2 s, 18.9 s, 20.0 s, and 20.5 s when
  it cleared, over 133 m, 100 m, 59 m, and 118 m. `2026-11-09-150052`
  began 28 s after its ignition came on with the bit already clear, too
  soon for the car to have been driven for 20 s. Inference: tyre-pressure sensors sleep when
  parked, wake after some seconds of rolling, and stay awake for minutes
  after a stop, which fits all of it. Two lists for other cars guess the
  passenger's seatbelt instead; neither tested it. BRZ logs: the bit was
  clear throughout the BRZ's drive, from its first frame.
- **Outside temperature:** `282` byte 4 minus 40 looks like outside
  temperature in °F. It read 108 at the start (68 °F, 20 °C) when PID `46`
  read 21 °C, and 90 (50 °F, 10 °C) when PID `46` read 10 °C. `282` byte 3
  follows byte 4 more slowly, like a dashboard display that only updates
  while moving. Inference: no PID reports °F to compare against. `3D1`
  carries the same reading [more finely](#outside-temperature-in-3d1).
- **Passenger's seatbelt:** the WRX list has `282` byte 5's low two bits as
  the two seatbelts, which makes b5.1, next, the passenger's. Nobody sat
  there in any recording, so that is untested, and the bit clearing itself
  below doesn't look like a buckle.
- **Vehicle stopped:** `375` b0.4 sets when wheel speed falls to about
  3 km/h and clears above about 5 km/h, at every stop in the drive.
- **Engine start:** `0D3` b1.6 is set from 4.6 s to 5.7 s, while the starter
  would be cranking. `0D3` byte 3, `152` b0.5, `361` b1.5 and b4.5, and `370`
  b2.5 and b7.7 also change once during the start. 2026-11-11: `361` b1.5
  is the check engine lamp going out and b4.5 the engine beginning to turn;
  see [A 2015 WRX's ECU](#a-2015-wrxs-ecu).

### Counters and fuel level

2026-11-09. Three bytes that changed once in the first drive hold values
that carry over from one recording to the next. All three readings are
inferences from the values below; nothing polled reports the same thing
except fuel level.

| Recording | `144` byte 3 | `144` byte 4 | `156` byte 7 | Fuel level (PID `2F`) | `660` bytes 2-3 | `282` bits 0-11, median |
|---|---|---|---|---|---|---|
| `2026-10-24-idle` | `03` | `02` | 24 | 70% | `6E70` | 308 |
| `2026-10-28-drive` | `23`, then `24` | `0B`, then `0C` | 35, then 34 | 97%, falling to 94% | `6E91` | 97 |
| `2026-10-29-did-drive` | `2D` | `0E` | 29 | 87-89% | `6E9B` | 179 |
| `2026-10-30-did-drive` | `2F` | `0F` | 28 | 82%, falling to 78% | `6E9D` | 181 |
| `2026-11-09-144943` | `65` | `23`, then `24` | 17, then 16 | 57%, falling to 55% | `6ED4` | 444 |
| `2026-11-09-150052` | `66` | `24` | 16 | 55%, falling to 53% | `6ED5` | 452 |
| `2026-11-09-194644` | `6A` | `24` | 15 | 51% | `6ED9` | 483 |

- **`144` byte 3 is probably a count of engine starts.** It rises from each
  recording to the next, by 1 between the two drives made back to back on
  2026-11-09, and its one change within a recording came 1.4 s after the
  engine started in `2026-10-28-drive`, the only recording that includes a
  start.
- **`144` byte 4 is probably a count of warm-up cycles.** It stepped once in
  each of the two drives that began cold (coolant 48 °C and 36 °C), 1.7 s
  and 2.7 s after `360`'s coolant temperature first read 71 °C. It didn't
  move in the three drives that began warm (84-98 °C) or in the cold idle,
  which only reached 55 °C. OBD-II counts a warm-up cycle when coolant rises
  by at least 22 °C and reaches 70 °C.
- **`156` byte 7 follows fuel level,** at about 2.4% of the tank per count
  (PID `2F` = 2.37 × count + 15.3 over all 1,168 replies, r = 0.987). The
  line doesn't pass through zero, so it is decoded as the raw count, not as
  a percentage.
  It falls as PID `2F` does, and rose with it between `2026-10-24-idle` and
  `2026-10-28-drive` (70% to 97%, so the tank was filled). PID `2F` wanders by a few
  percent as the fuel moves, and `2026-10-29-did-drive` sits about 5% above
  the line. Both drops within a recording came 475 s in, which looks like
  coincidence.

`inferred-signals` decodes the three as `:engine-starts`, `:warm-up-cycles`,
and `:fuel-gauge`, each as its raw byte.

2026-11-10, the last two columns:

- **`660` bytes 2-3 probably count ignition cycles.** Big-endian, they
  rise from each recording to the next by 33, 10, 2, 55, 1, and 4, and
  never within one. `144` byte 3 rose by 33 (counting its step in the
  first drive), 9, 2, 54, 1, and 4, so the two keep step and `660` gained
  two more: switching the ignition on without starting the engine would do
  that. It read 28,272 in the first recording. Bytes 0-1 are always `FF FF`.
  2026-11-11: the WRX's ECU stamps each stored trouble code with bytes 2-3
  and with bytes 5-7, which fits a count of ignition cycles and a time
  within one. Its author guessed bytes 5-7 are a distance; here they count
  ten a second while parked.
- **`282` bits 0-11 are the fuel sender, undamped.** Byte 0 plus the low
  nibble of byte 1, little-endian. The value rises as the tank empties, by
  about 8.3 counts for each 1% on PID `2F` across the seven recordings.
  Parked it stays within 8 and 30 counts (304-312, 468-498); driving it
  swings by a hundred or so as the fuel moves, rising under acceleration, which
  is what first looked like a flag in byte 1's low bits. `156` byte 7 is
  the same level damped, in steps 20 times as coarse. `inferred-signals`
  decodes it as `:fuel-sender`, the raw count.

### Outside temperature in `3D1`

2026-11-10. `3D1` bytes 0-1, little-endian, are `282` byte 4's outside
temperature five times as fine: 3600 + 5 × byte 4, fitted over 1,476
samples from all seven recordings as slope 4.999 and intercept 3600.0
(r = 0.9987), with byte 4 from 90 to 126 (50-86 °F). So a count is 0.2 °F,
and the temperature is (value − 3600) ÷ 5 − 40 in °F, or
(value − 3600) ÷ 9 − 40 in °C. The values seen run from 4,047 to 4,229,
which is why byte 1 flickered between `10` and `0F`: the reading was
crossing 4,096. `inferred-signals` takes `:outside-temp` from here. It
leaves out bit 15, which opendbc has as a units flag (metric or imperial)
and which was never set; whether the scale changes with it is unknown.
2026-11-11: the WRX's ECU reads only bits 0-12, takes `1FFF` to mean no
reading yet, and sets a sensor fault below 3,523 or above 4,873, which on
this scale are −49 °C and 101 °C. Bits 13 and 14 were never set here, so
the two readings can't be told apart, and the decoding is unchanged.

### Lighting bits in `152` byte 7

2026-11-09. `152` byte 7 is opendbc's headlights and wipers byte. Its bits
1-3 read `0C` throughout five recordings, `00` in `2026-10-24-idle`
(parked, parking brake on), and in `2026-10-30-did-drive` `00` with the
parking brake on and `02` with it off, in all 43,194 frames. Inference:
b7.2 and b7.3 are the light switch, and b7.1 is the daytime running lights,
which this car turns off with the parking brake on. The position of the
light switch wasn't noted in any recording, so this is untested.

b7.6 pulsed eight times in the two 2026-11-09 drives, for 1.30-1.38 s each
time, while moving, and never in the others. Nothing else on the bus
changes with it. A pulse of fixed length could be a single wiper sweep;
that is a guess. 2026-11-10: opendbc names this bit Wiper, and the WRX
list has `C0` in this byte for wipers on, so two other cars agree. It is
still untested here. BRZ logs: the bit pulsed once in the BRZ's drive, for
1.5 s.

2026-11-10: `375` b3.3 goes with the lights. It was set throughout the
five recordings in which `152` byte 7 read `0C`, and clear throughout the
two in which it read `00` or `02`. It never changed within a recording, so
that is seven agreeing readings, not a test. The WRX list reads this byte
of `152` as `80` with nothing on, plus `04` for parking lights, `0C` for
headlights, and `18` for high beams, which fits the light-switch reading
above; this car's byte has no `80`.

BRZ logs: in a drive where the lights were switched on purpose, b7.2 is
the parking lights and b7.3 the low beams, b7.4 comes on with b7.3 in
short flashes, as the high beams' flasher would, and b7.1
is set exactly while the parking brake is off and the lights are off, as
daytime running lights would be; see
[A 2017 BRZ's logs](#a-2017-brzs-logs). That is the reading above, bit for
bit, seen on a car where someone noted what they did.

2026-11-11-200210: tested here. With the parking brake on, the byte read
`00` until the light switch was turned to its first position, `04` from
then (3.48 s), and `0C` from its second position (7.18 s). `375` b3.3 set
with b7.2, in a frame sent at once, off its 1 s beat. So b7.2 is the
parking lights and b7.3 the headlights, decoded as `:parking-lights` and
`:headlights`. `04` alone hadn't been seen before. The daytime running
lights (b7.1), the high beams, and the wipers are still untested.

### Doors

`2026-11-09-194644` was recorded parked with the engine running. The
driver's door was open at the start; it was closed, and then the front
passenger, rear passenger-side, and rear driver-side doors were each opened
and closed in turn, and finally the driver's door again. One bit of `374`
byte 3 follows each door, and nothing else in the recording changes with
them except the signals listed after the table:

| Bit | Open | Closed | Door |
|---|---|---|---|
| `374` b3.0 | from the start | 8.51 s | Driver's (front left) |
| `374` b3.1 | 14.37 s | 20.61 s | Front passenger |
| `374` b3.2 | 27.57 s | 33.96 s | Rear passenger-side (rear right) |
| `374` b3.3 | 38.17 s | 45.12 s | Rear driver-side (rear left) |
| `374` b3.0 | 49.55 s | 55.93 s | Driver's again |

Front left and right assume a left-hand-drive car, as this one is.
opendbc names b3.0 DOOR_OPEN_FR, but here it is the driver's door, so
opendbc's door names don't fit this car. b3.0 being set throughout
`2026-10-24-idle` probably means the driver's door was open then.

These change with the doors too:

- **`375` b1.0-b1.3** copy `374` b3.0-b3.3 exactly.
- **`154` b6.0** is set whenever any door is open.
- **`374` b4.7** clears while any door is open, here and in the two other
  recordings in which a door bit was set briefly (`2026-10-30-did-drive`
  and `2026-11-09-150052`). In all three, `374` b3.7 was set throughout. It
  doesn't simply mean "all doors closed": in `2026-10-24-idle` b4.7 was set
  with b3.0 set and b3.7 clear, and in the drives, with no door bit set,
  it followed b3.7. One rule covers all of it: b4.7 is b3.7 XOR "any of
  b3.0-b3.3 is set", in every one of the 2,953 `374` frames in the seven
  recordings. What b3.7 means is still unknown (see
  [Still unexplained](#still-unexplained-or-untested)).
- **`375` b3.2** pulses for 20 ms each time a door closes.
- **`375` b3.0** clears when a door closes and sets 2.00 s later, and
  **`375` b3.1** clears 2.00 s after a door closes and sets when one opens.
  Inference: a timer started by the door closing, perhaps for the interior
  light.

`374`, `375`, and `154` are sent once a second and also as soon as a door
changes: each change came 210-960 ms after the frame before, off the 1 s
beat.

### Lights, blower, and air conditioning

`2026-11-11-200210` was recorded parked, with the parking brake on and the
engine on a cold fast idle (1,690-1,900 rpm, coolant 45-50 °C). In order,
the light switch was turned to its first position and then its second, the
blower was turned to speeds 1, 2, 3, and 4, the recirculation was switched
on, and the air conditioning was switched on. The times weren't noted, only
the order. Six bits changed with them, each once:

| Time | Bit | Reading |
|---|---|---|
| 3.47 s | `152` b7.2 and `375` b3.3 set | Light switch to its first position: the parking lights |
| 7.18 s | `152` b7.3 sets | Light switch to its second position: the headlights |
| 10.14 s | `140` b7.5 sets | The blower switched on, at speed 1 |
| 24.56 s | `360` b6.7 sets | The air conditioning switched on |
| 24.86 s | `360` b6.4 sets | The radiator fan, which runs with the air conditioning |
| 25.12 s | `140` b1.6 sets | The compressor engages |

The lights are in [Lighting bits](#lighting-bits-in-152-byte-7).

**Air conditioning.** The three bits are where the
[WRX's ECU](#a-2015-wrxs-ecu) has the request, the radiator fan's first
stage, and the compressor's output, and none had been set in any earlier
recording. The engine shows the compressor's load: `141`'s engine torque
went from 13.5 Nm (22-24.4 s) to 25.7 Nm (27-32 s) at about 1,780 rpm,
which is 2.5 kW to 4.8 kW, with the manifold pressure up from 28 to
34 kPa and the fuel flow from 0.43 to 0.53 g/s. The torque began to rise
at 24.5 s, with the request and before the compressor's bit. `360` b6.6,
the WRX's air conditioning switch pin, stayed clear. They are decoded as
`:ac-request`, `:radiator-fan`, and `:ac-compressor`. The fan has not been
seen to come on for a hot engine, or to stop.

**Blower.** `140` b7.5 is, on the WRX, the flag that lets the idle air be
raised for an accessory, from a pin of the ECU or from another message.
Inference: here it is the blower's switch. It set 3 s after the headlights
and 14 s before the air conditioning, where the blower's first speed falls
in the order, and it is the only flag to change in that gap. It fits the rest: in
the seven earlier recordings it was set once, for 1.9 s, and on the
[BRZ](#a-2017-brzs-logs) it was set throughout but for 2 s at the restart,
when a blower left on loses its supply. Whether the rear window heater or
anything else sets it hasn't been tried. Decoded as `:accessory-load`.

**Not on the bus:** the blower's speeds 2, 3, and 4 and the recirculation.
No bit changed between 10.14 s and 24.56 s but counters, parity bits,
sensor noise, [`660`'s timer](#message-structure), and the outside
temperature falling 1 °F at 16.68 s. The car's climate controls are
manual, so this is what one would expect. The engine's torque, which
falls steadily as the engine warms, has bumps of about 0.5 Nm near 13.8,
16.7, 20.6, and 22.7 s, spaced as the four steps would be; the parking
lights and the headlights made bumps of 1 and 1.8 Nm at 3.5 and 7.2 s.
But the idle wanders by as much, so they can't be told from it. The
[battery current](#battery-current-and-temperature-in-361) shows no step
at any of the nine: the alternator covers each load within its ripple.

`361` byte 7 began to flicker from 132 to 131 at 24.06 s, half a second
before the air conditioning's bit, and settled at 131 by 29 s. It drifts
by more than that in every recording, so this is probably chance.

The polling stopped for 2.3 s in this recording, from 17.36 s to 19.66 s:
no request was sent, while the car's own frames kept arriving, none lost.
The computer's clock was set back 2.3 s at that moment, and the polling
was paced on it; see
[the recording's entry](recordings.md#2026-11-11-200210). The PIDs have no
samples for those 2.3 s, and nothing else is affected.

## Worked out from the decoded signals

Second pass. None of these is a new signal: each is arithmetic on the ones
above, and several double as checks of their scales against the car's
published figures (Subaru's
[2015 Impreza specifications](https://s3.amazonaws.com/subarumedia.iconicweb.com/mediasite/specs/2015_Impreza_spec_sheet_9_24_Final.pdf)).
The library computes five of them as it decodes; see
[Derived signals](#derived-signals). The rest are in the table only.

| Quantity | From | In the five drives | Check |
|---|---|---|---|
| Engine power | `141`'s engine torque × `140`'s RPM | Peak 109.8 kW at 6,300 rpm in `2026-10-28-drive`, the only pull to the red line | Rated 148 hp (110 kW) at 6,200 rpm |
| Gear ratios | RPM ÷ mean wheel speed, in gear with the clutch out | 123.2, 67.2, 46.1, 34.55, and 26.22 rpm per km/h, the same to 0.2% in every drive | Each step is within 0.3% of the gearbox's 3.454, 1.888, 1.296, 0.972, and 0.738 |
| Tyre rolling circumference | 5th gear's figure, the 0.738 ratio, and the 4.11 final drive | 1.93 m | A 205/55R16 is 1.985 m unloaded; which tyres are fitted wasn't noted |
| Clutch slip | RPM against wheel speed × the gear's figure | Not tabulated | |
| Fuel used per unit of work | `360`'s fuel flow ÷ power, in steady 2 s windows with a warm engine | 250-300 g/kWh at the median, 212-228 at best, over 400 below 40 Nm | Usual for a port-injected engine, so the torque and fuel-flow scales agree with each other |
| Idle fuel use | Fuel flow, stopped | 0.6-1.1 l/h, and 1.7 just after the cold start | |
| Time in fuel cut | `141` b7.3 | 17-22% of the time moving | |
| Road grade | Longitudinal acceleration less the rate of change of speed, ÷ g | Within −4.6% and +3.9% for nine-tenths of the time moving | Summed over distance, the height agrees with [`361`'s barometer](#unexplained-engine-bytes) |
| Steering ratio | Steering angle against curvature × the 2.645 m wheelbase, with a term for lateral acceleration | 14.3-14.6 to 1, and 1.9-2.8° more at the front wheels for each g | Rests on the yaw scale, which is [in doubt](#speed-brakes-and-chassis) |
| Tyre differences | Each wheel's speed ÷ the four's mean, on straights at light load | Front left 0.07-0.08% fast, rear left 0.06-0.08% slow, the right pair within 0.02% | The same in every drive, two weeks apart |
| Mass over drivetrain efficiency | Torque × gearing ÷ 0.307 m against the longitudinal sensor, which leaves the grade out, in 1 s windows | 1,400-1,540 kg in 2nd to 5th (r² 0.97-0.98) | The car is about 1,350 kg empty; the [fit against speed](#torque-in-141) gave 1,560-1,710 |

The drag left over in that last fit is about 0.2 m/s² plus 0.0003-0.0005 ×
speed² (in m/s) in 4th and 5th, the right size for the car but too loosely fixed
to quote as a drag coefficient. The clutch is only down for a second or
two at a time, 155 s in all, so there is no real coast-down in the
recordings.

The gear figures also give `141`'s 1st gear properly: 123.2, not the 124
that the launch data [suggested](#gear).

### Derived signals

`derived/derived-signals` are worked out as events arrive, by the live and
replay sources and by `series/recording-series`, so the dashboard and its
map have them. Each is a `{:type :signal ...}` event with no `:can-id`,
sent after the event that completed it and at most 20 times a second, from
the latest of the other values if that is under a second old. The
dashboard shows them in their own section, tagged derived.

| Signal | Worked out as | Sent with | No value (nil) |
|---|---|---|---|
| `:engine-power` | Engine torque × RPM × π ÷ 30,000, in kW; negative on the overrun | `141`'s torque | |
| `:clutch-slip` | RPM less speed × the gear's rpm per km/h (123.2, 67.2, 46.07, 34.55, 26.22), in rpm | `141`'s torque | In neutral, and in reverse, whose ratio wasn't measured |
| `:fuel-economy` | Fuel flow ÷ 745 g/l ÷ speed, in l/100 km | `360`'s fuel flow | Below 5 km/h |
| `:road-grade` | The mean longitudinal acceleration over the last second, less the change in `0D1`'s speed over it, ÷ g, in % | `0D0`'s acceleration | Below 10 km/h, in reverse, for a second after the yaw rate reaches 8°/s, and until most of a second has been seen |
| `:injector-duty` | Pulse width (DID `10A3`) × RPM ÷ 1,200, in %: each injector opens once in two revolutions | The DID's reply | Not sent at all without DID polling |

Run over the recordings, they give what the second pass found by hand:
power peaks at 109.8 kW in `2026-10-28-drive` and 102.4 kW in
`2026-10-30-did-drive`, clutch slip sits at a median of 0 rpm with 90% of
it within −37 and +71 rpm in the first of those, and the injectors reach
62% duty in the second.

What to keep in mind when reading them:

- **Clutch slip** is only as good as `141`'s gear, which holds the old gear
  through part of a shift, so a shift shows as a spike of several hundred
  rpm either way. Stopped in gear with the clutch down it reads the idle
  speed, since the gearbox isn't turning.
- **Road grade** is wrong in a tight turn, so it has no value there. The
  sensor sits ahead of the rear axle, and in a turn it feels part of the
  pull towards the centre as slowing down. What the sensor feels less what
  the wheels show falls by 1.5-2.2 m/s² for each (rad/s)² of yaw rate, in
  all five drives (r = −0.53 to −0.76 over the windows with a turn in
  them). Left in, that read as low as −8% to −13% in the tightest turn of
  each drive; at 8°/s it is 0.4%. With turns left out, 6-11% of the time moving, the two
  drives checked stay within −7.4% and +6.1%. The sensor's zero isn't
  known either, so a constant error of a fraction of a percent is likely,
  and the body's pitch adds a little under braking and acceleration.
- **Fuel economy** is 0 in fuel cut and passes 100 l/100 km pulling away.
- **Power** rests on the torque scale, which is inferred, though the peak
  matching the engine's rating supports it.

They add about a tenth to the time `recording-series` takes.

### Leads from other cars

Second pass. Published for related cars, and untested here because the
bits never changed in any recording:

| Where | Said to be | Source |
|---|---|---|
| `374` b3.4 | Hatch open | Old opendbc Outback file; Forester SJ adapter |
| `282` b5.4, b5.5 | Turn signals; the 2015 and 2016 Outback files disagree on which is left | Old opendbc Outback files |
| `152` b7.4 | High beam | Old opendbc Outback file |
| `144` b6.0, b6.1 | Cruise on, cruise active | Old opendbc Outback files |
| `360` b5.4, b5.5, byte 7 | Cruise on, cruise set, and the set speed | timurrrr's BRZ notes, "not tested much" |
| `376` byte 0 | Dashboard dimmer level, 0-255; this car's constant `FA` fits | Forester SJ adapter |
| `372` | Airbag status | open-can-db |

They agree with what was found here on `375` b3.3 (lights on), `152`
byte 7 (the light switch), `141` byte 7 (16, then 8, then 0 on lifting
off), and `282`'s fuel sender. Nothing published explains `0D0` bytes 4-5
or `361` bytes 3 and 5-7, so those readings rest on the recordings alone.
2026-11-11: the WRX's ECU, below, gives `361` byte 3.

### A 2015 WRX's ECU

2026-11-11. The DBC file `extras/can/FA20DIT_LF75404S.dbc` in
`github.com/Jengacat/subaru-dit-defs` lists the messages a 2015 WRX's ECU
(ROM `LF75404S`) sends and reads, and where in each it keeps every bit,
worked out from the ROM. It is one person's reading of a disassembly, for
a turbocharged engine with a different ECU, and it hadn't been tried
against a car. So a name from it counts here only where the recordings
bear it out. It has nothing on messages the ECU neither sends nor reads:
`002`, `154`, `156`, `371`, `374`, `375`, `376`, and `6FA`.

Its layouts of `0D1`, `0D4`, `140`, `141` bytes 0-6, `144`, and `360`
agree field for field with what is already decoded here: speed, wheel
speeds, pedal, clutch, RPM, throttle, both torques, gear, the two
counters, fuel flow, and the temperatures and manifold pressure. Its
scales agree too, except the fuel flow's (below).

Borne out by the recordings:

| Where | The WRX's ECU | Here |
|---|---|---|
| `144` byte 2 | The most torque available, less friction, 1.6 Nm per count | With the pedal on the floor, `141`'s engine torque is 96-101% of it in every 1,000 rpm band of all five drives (median of each band; about 2,000 samples), and over a drive the two peak at 182-186 and 182-185 Nm. Decoded as `:available-torque`. |
| `361` byte 3 | Barometric pressure, 0.4 kPa per count plus 13.2 | Rounds to PID `33` in 564 of its 633 replies and is within 1 kPa in the rest, over bytes 214-218 (98.8-100.4 kPa). Decoded as `:barometer`. |
| `361` b1 bits 5-7 | The check engine lamp: 0 lit, 1 off, 4 flashing | 0 from ignition on in `2026-10-28-drive`, 1 from 4.64 s, as the starter began to crank, and 1 in every other frame of the seven recordings. Decoded as `:check-engine-lamp`. |
| `361` b4.5 | Engine not rotating | Set in 91 frames, all of them among the 97 with `140` at 0 rpm. Decoded as `:engine-stopped`. |
| `141` b6.3, b6.4 | Two flags held until the supply voltage is up and a timer has run out after a start | Both set from ignition on and cleared together at 5.40 s, 0.55 s after the engine first turned. |
| `144` b1.1 | A drive cycle is done: counters at 600, 300, and 30 | See [Drive-cycle flag](#drive-cycle-flag). |
| `144` bytes 3, 4 | Counts of engine starts and of a coolant event, kept with the ignition off | See [Counters and fuel level](#counters-and-fuel-level). |
| `0D2` bytes 0-1, 2-3 | The stability control's torque request and torque limit, as `141`'s torques are scaled; the request counts only above 10,000 with b4.1 set | `00 00` and `FF FF` in every frame: nothing asked for and no limit. They should move when the traction control cuts in, which no recording has. |
| `144` b6.7, `360` b6.0 | Set on a car coded as a manual | Set in every frame. |
| `370` bytes 0-1, `360` b6.1 | A load the engine adds idle air for, and a flag while it is high | See [Steering](#steering). |

The barometer stays in `inferred-signals`, though it follows a PID: five
values of the byte, 1.6 kPa apart, can't tell 0.4 kPa per count from the
0.5 found before, so the scale is the WRX's and not this car's own. A
drive over a few hundred metres of height would settle it, and then it
belongs in `broadcast-signals`.

2026-11-11-200210: three more, from the table below as it stood, were
borne out when the air conditioning was switched on: `360` b6.7 (the
request), `360` b6.4 (the radiator fan's first stage), and `140` b1.6 (the
compressor), in that order within 0.6 s; see
[Lights, blower, and air conditioning](#lights-blower-and-air-conditioning).

Named, in bits that never changed in the first seven recordings, so
untested except where it says:

| Where | The WRX's ECU |
|---|---|
| `144` b0.2-b0.7 | The cruise control's lever switches. `C0` throughout here. |
| `144` b6.0, b6.1, b6.2 | Cruise standing by, controlling, and overridden by the pedal |
| `144` b6.5 | Cruise unavailable. Set throughout here, and on a BRZ while its cruise control was working, so the name doesn't hold. |
| `360` b5.4, b5.5, byte 7 | The cruise lamp, the SET lamp, and the set speed, 0 with the main switch off |
| `140` byte 4 | The pedal position the cruise control is asking for, 0 when it isn't |
| `360` b6.4, b6.5 | Radiator fan, first and second stage. 2026-11-11-200210: b6.4 set with the air conditioning. |
| `360` b6.6, b6.7; `140` b1.6 | The air conditioning's switch and request; its compressor engaged. 2026-11-11-200210: b6.7 and then b1.6 set when it was switched on, and b6.6 didn't. |
| `140` b1.4, b1.5 | Check engine lamp requested; electronic throttle fault |
| `140` b3.7 | A tester has switched off the setting of trouble codes |
| `361` byte 2 | Brake booster pressure. 0 throughout here, so probably no sensor. |
| `282` byte 6 high nibble | A market or units code. 2 throughout here. |

The cruise and lamp names agree with the leads above from other cars.

Other things it says that fit:

- **`140` byte 5** is the pedal position the torque request uses: the
  pedal, or the cruise control's if that is higher. Here it equals byte 0
  in 93-100% of frames.
- **`144` b6.3** is a switch input the WRX's author couldn't name. Here it
  is the brake pedal.
- **`152` b7.2 and b7.3** each add idle air, as an electrical load would.
  That fits their being the [light switch](#lighting-bits-in-152-byte-7).
  The ECU also reads b6.2, b6.3, and most of the rest of bytes 6 and 7.
- **`282` byte 3** picks between two fan tables at 148, which would be
  108 °F if it is the [outside temperature](#body) as byte 4 is.
- **`140` b7.5,** seen once here for 1.9 s, is a flag that lets the idle
  air be raised for an accessory. Nothing here shows which.
  2026-11-11-200210: it set when the blower was switched on.

Where it doesn't carry over to this car:

- **`141` byte 7** holds the WRX's drive mode. Here b7.3 is
  [fuel cut](#pedal-and-fuel-cut-flags), in 99.7% of frames.
- **`141` b6.5** is "below the rev limit" there. Here it is set at idle
  and comes and goes at 2,000-2,500 rpm in `2026-10-28-drive`.
- **`360` b5.6** latches once the engine is warm there. It was never set
  here, at up to 96 °C.
- **`361` bytes 5-7** are zeros there, and the current is in `362`; see
  [Battery current and temperature](#battery-current-and-temperature-in-361).
- **`360` bytes 0-1:** its fuel flow has a scale of 0.001 in a unit its
  author hadn't settled. The 0.00072 g/s here is fitted to this car.
- **`0D0`:** it has only bytes 6 and 7, with no meaning found, so it says
  nothing about the yaw rate or bytes 4-5.

### A 2017 BRZ's logs

2026-11-11. `github.com/painellope/BRZ-2017` holds raw CAN logs from a
2017 BRZ, a manual (it has no `148` or `149`). The first-generation BRZ shares this bus
layout, which is why the RaceChrono notes were useful, and these logs are
the recording this page keeps asking for, made on that car:
`rdcan_2024-10-10_09-09-10.csv` is 7.5 minutes in which its owner switched
the headlights, high beams, and fog lights, used the handbrake and the
cruise control, pressed the stability control's buttons, opened a door,
unbuckled, and stopped and restarted the engine. A second log locks and
unlocks the car.

They have limits. The owner listed what was done but not when, so each
event below is placed by bits that move together and by what is already
known. The logs keep about one frame in three (18 a second of `0D0`'s 50),
so anything shorter than a few tenths of a second can be missed. And it is
another car: a bit that moves there says where to look here, and no more.

The BRZ sends 18 of this car's 24 IDs. It lacks `002`, `154`, `371`,
`376`, `660`, and `6FA`, and has 19 this car lacks. `144` carries the same
constants as here (`C0` in byte 0, `A4` in byte 5, `A0` in byte 6), where
the WRX's ROM has `24` in byte 5.

What agrees with this car:

| Where | In the BRZ's drive |
|---|---|
| `152` b6.3, b6.2 | Parking brake off at 8 s and on at 408 s; reverse from 7 s to 23 s, backing out |
| `282` b5.0 | Clear from 5 s, and set for a second at 252 s: the driver's seatbelt |
| `374` b3.0, `375` b1.0 | Set together three times for 1-6 s: a door, the driver's by the notes it came with |
| `375` b3.3 | Follows `152` b7.2, the parking lights |
| `141` b6.3, b6.4; `361` b1.5, b4.5; `0D3` b1.6; `370` b2.5 | All change at the restart, 436-439 s, as they do at this car's start; the check engine lamp's code reads 0 for a second and then 1 |
| `144` byte 3 | Steps by one at 438 s, a second after the engine restarted |
| `370` b1.0, `360` b6.1 | Set together while manoeuvring at 11-24 s |
| `361` bytes 5-7 | See [Battery current and temperature](#battery-current-and-temperature-in-361) |
| `0D0` bytes 4-5 | Updated every 60 m; see [Speed, brakes, and chassis](#speed-brakes-and-chassis) |
| `0D2` | `00 00 FF FF 00 00 00 00` throughout |

What it shows that has never happened in a recording here:

| Where | In the BRZ's drive | Here |
|---|---|---|
| `152` b7.2, b7.3 | b7.2 at 73-79 s, 84-91 s, and 352-355 s, and b7.3 within the first two, at 75-77 s and 85-91 s: the parking lights and the low beams | The [light switch](#lighting-bits-in-152-byte-7) reading: `0C` is the headlights on |
| `152` b7.4 | Sets with b7.3 for a second or less, at 82 s and four times at 132-134 s, with the parking lights off, as flashing the high beams does. It was never set alone or for longer. | Never set |
| `152` b7.1 | Set while the parking brake is off and the lights are off, in 99.5% of 7,583 frames with the ignition on | The daytime running lights, as read here |
| `144` b6.0, `360` b5.4 | Set together from 43.5 s: the cruise control switched on | Never set |
| `144` b6.1, `360` b5.5 | Set together nine times, for 0.4-42 s: a speed set | Never set |
| `360` byte 7 | 49-61 while a speed is set, 5% above the car's speed in km/h (54 at 51.1), as a speedometer reads | Always 0 |
| `144` b0.2 | A press of the main switch: `144` b6.0 changes at each of its nine pulses | Never set |
| `144` b0.4, b0.5 | Other presses of the lever. Six of b0.5's nine pulses come at the moment a set speed ends. | Never set |
| `144` b6.2 | Sets as b6.1 does at six of the nine, for 0.1-3 s | Never set |
| `374` b1.6, b1.7 | Set at 84-91 s and 86-91 s, with the lights on: the fog lights, front and rear in some order. timurrrr's notes have b1.7 as fog lights. | `374` byte 1 is `08` throughout |
| `0D3` b0.3, b0.5, b1.3 | b0.3 and b1.3 set together twice, for 4 s and 1 s, at 258-267 s; b0.5 at 397-404 s, stopped, with b1.3 from 400 s. Probably the stability and traction control buttons, which the owner says were pressed. | `0D3` byte 0 is 0 throughout |
| `372` b2.0, b2.1 | Set for 6 s after the restart. open-can-db has `372` as the airbag's status, and an airbag lamp checks itself like that. | 0 through this car's one recorded start |

Where the BRZ's body differs, so nothing carries over:

- **Locks:** `375` byte 0 reads `03` unlocked and `00` locked, and byte 2
  pulses `21` as it locks and `A1` or `B1` as it unlocks. Here `375`
  b0.0, b0.1, and byte 2 are 0 throughout, with the doors opened and shut.
- **`156`** is all zeros there. Here byte 7 is the fuel gauge.
- **`375` b3.2** is set while a door is open and while the car is unlocked
  with the engine off. Here it pulses for 20 ms as a door closes.
- **`152` byte 7** has `80` set throughout, as the WRX list has it, and
  this car's never has.
- **`140` b7.5** is set throughout, but for 2 s at the restart. Here it
  was set once, for 1.9 s. 2026-11-11-200210: here it sets with the
  blower, so the BRZ's was probably on, which does carry over.

Two things it adds about the engine's messages:

- **The barometer is read at the start.** `361` byte 3 held 215 through
  the drive and read 221 from the restart, 2.4 kPa more on the WRX's
  scale. Inference: the ECU takes the pressure from the manifold sensor
  before the engine turns and works it out as it can afterwards. Here it
  moved by a count or two within a drive.
- **Nothing in `282` flashes.** `282` b5.4 and b5.5, which the old Outback
  files have as the turn signals, never changed.

## Still unexplained or untested

- **`374` b3.7 and b4.7, and `375` b3.7,** were set until 92.5 s in
  `2026-10-28-drive` and then cleared together while moving at about
  54 km/h; both `374` bits were clear throughout `2026-10-29-did-drive` and
  set in the later recordings. b4.7 is b3.7 XOR any door open (see
  [Doors](#doors)), so the unknown is b3.7, which `375` b3.7 followed at
  92.5 s.
- **`374` b3.4,** the fifth of opendbc's door bits, was clear in all seven
  recordings. It may be the hatch (a guess; the hatch hasn't been opened
  while recording).
- **Blinkers:** `282` bits 44-45 never changed in any of the seven
  recordings, five of them drives. No bit of any message sent every 100 ms
  or faster flashes at a blinker's rate in `2026-10-30-did-drive` or
  `2026-11-09-150052` either (runs of 0.25-0.55 s; only counter bits
  qualify). Unless no blinker was used, they aren't in those messages. When
  one was used wasn't noted. The WRX list has them in `152` byte 7 (`10`
  left, `20` right); those two bits were never set here either. BRZ logs:
  no bit flashes at that rate in seven minutes of a BRZ's drive to work
  either, and `152` b7.4 goes with its high beams. The turn signals
  probably aren't on this bus.
- **Daytime running lights, high beams, and wipers** (`152` byte 7): see
  [Lighting bits](#lighting-bits-in-152-byte-7); those readings are
  untested. The light switch's two positions have been tested.
- **Cruise control** (`144`): no change that looks like it has been seen.
  Whether it was used while recording wasn't noted. The bits the WRX's ECU
  uses for it never changed, so it probably wasn't. They are the bits that
  move on a BRZ when its cruise control is used; see
  [A 2017 BRZ's logs](#a-2017-brzs-logs).
- **`144` bytes 3 and 4 and `156` byte 7:** see
  [Counters and fuel level](#counters-and-fuel-level); the readings there
  are inferences.
- **What switches on `141`'s RPM filter** (see
  [`141` engine speed](#141-engine-speed)): it needs the car stopped, the
  pedal released, and a warm engine, and then something more, probably
  time.
- **The battery current's scale,** and whether **`361` byte 7** is the
  battery's temperature (see
  [Battery current and temperature](#battery-current-and-temperature-in-361)).
- **The yaw rate's scale,** which the wheels put 7% lower, the exact sum
  behind **`0D0` bytes 4-5,** and **`0D2` b5.5,** seen once (see
  [Speed, brakes, and chassis](#speed-brakes-and-chassis)).
- **`002` byte 2** changes only as the engine starts.
- **`370` b7.7** is clear after the ignition comes on and sets once: 31 s
  after it (as the engine started), 83 s, and 226 s in the three
  recordings that caught it, flickering as it does in the last two.
  **`370` b3.6** flickers through every drive.
- **`140` b7.5** was set once, for 1.9 s, cruising at 110 km/h in
  `2026-11-09-144943`. Nothing else changed with it. On the WRX it lets
  the idle air be raised for an accessory. 2026-11-11-200210: it sets with
  the blower. What else sets it, and what set it for those 1.9 s, is
  unknown.
- **`141` b6.5** after a shift (see [Gear](#gear)): the clutch bit doesn't
  explain it.
- **The drive-cycle flag and the ignition counter** are readings that fit,
  not tested ones, and **the barometer's scale** has been seen over only
  1.6 kPa.
- **The radiator fan's second stage, the air conditioning's switch pin
  (`360` b6.5, b6.6), and a flashing check engine lamp** have names from
  the WRX and have never been seen. The fan's first stage has been seen
  only with the air conditioning on.
- **`376`**: constant.

## Next recording

A recording with these done one at a time, and a note of when, would settle
most of what's left:

1. Parked, engine running: the wheel straight ahead, then at full lock each
   way (which should also set the steering-load flags); the hatch opened
   and closed; each blinker, the light switch in each position, the high
   beams, and the wipers (one sweep, then on); the parking brake on and off
   with the lights off; the doors locked and unlocked; the clutch pressed
   and released in neutral, and reverse selected and left, to see the
   clutch and reverse bits with nothing else going on; the passenger's
   seatbelt buckled and unbuckled; and a few throttle blips.
2. A cold start left to idle past 71 °C, to test the warm-up counter in
   `144` byte 4 without driving.
3. The ignition switched on and off without starting the engine, to see
   `660`'s counter step alone, and a start on a hill, for `0D2` b5.5.
4. Parked at idle, a load of known size switched on and off (the rear
   window heater, the headlights, the blower), for the battery current's
   scale, with DIDs `1135`-`1137` polled if the ECU has them.
5. A full circle in a car park, slowly, for the yaw rate's scale, and a
   coast in neutral from highway speed on a level road, for the drag.

Add to the first: the cruise control's buttons, and note which side each
turn signal was on.

2026-11-11, for the names from the WRX:

6. Parked at idle, the air conditioning switched on and off, and then left
   idling until the radiator fan runs and stops.
7. A drive that climbs or drops a few hundred metres, with PID `33`
   polled, for the barometer's scale. Switch the ignition off and on at
   the top, to see whether the barometer steps as the BRZ's did.
8. Pulling away hard on a loose or wet surface, for `0D2`'s torque request.
9. In the first, also: the stability control's button, pressed and held,
   for `0D3` b0.3, b0.5, and b1.3; the fog lights, if fitted, for `374`
   b1.6 and b1.7; and the cruise control switched on, set at a steady
   speed, and cancelled, for the bits the BRZ moved.

2026-11-11-200210 did part of the first, fourth, and sixth: the light
switch's two positions, the blower, and the air conditioning switched on.
Still to do from those: each switched off again; the rear window heater,
for `140` b7.5; the lights with the parking brake off; and the idle until
the radiator fan runs with the air conditioning off. The loads moved the
battery current too little to give its scale with the engine running, so
the fourth is better done with the ignition on and the engine off.

The drive asked for here before has been made twice (2026-11-09), with PIDs
`45`, `49`, `4C`, and `65`: it confirmed `140` byte 6's scale and the gear
reading of 7, and left the pedal's scale inexact.
