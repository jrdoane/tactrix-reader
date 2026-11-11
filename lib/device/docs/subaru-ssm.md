# Subaru SSM reads

Some engine values, such as knock correction, are not standard OBD-II PIDs.
Subaru ECUs expose them through Subaru Select Monitor (SSM) memory reads.
`ssm-init` identifies the ECU and `ssm-read` reads its memory, both over the
same ISO15765 channel as [`obd`](obd-pids.md). Run these commands from
`lib/device`.

**These do not work on this project's 2015 Impreza.** Its ECU does not answer
SSM over CAN; see [This project's 2015 Impreza](#this-projects-2015-impreza).
The request formats below come from RomRaider's source
(`io/protocol/ssm/iso15765/SSMProtocol.java`) and public forum notes, and have
not been confirmed on any vehicle.

## Identifying the ECU: `ssm-init`

`ssm-init` sends traffic on the vehicle bus. It reads the ECU ID that logger
definitions use to look up ECU-specific addresses:

```sh
clojure -M:run ssm-init
clojure -M:run ssm-init /dev/cu.usbmodemTAlgJ3P81
```

It sends `AA` to `7E0` and expects a multi-frame reply from `7E8`: `EA`, 3
bytes of unknown meaning, the 5-byte ECU ID, then bitfields of the standard
SSM parameters the ECU supports. The result adds the ID and the bitfields to
the usual request and response (values here are from a test, not a vehicle):

```clojure
{:ok? true
 ...
 :ecu-id "7334384107"
 :capabilities {:bytes [243 250 201 142] :hex ["F3" "FA" "C9" "8E"]}}
```

The ECU ID is 10 hex digits. It is not the 8-character calibration ID, such as
`EA1T002C`, that names a ROM definition file, although some tools label the
calibration ID "ECU ID". A ROM definition's `<ecuid>` field links the two.

## Reading addresses: `ssm-read`

`ssm-read` sends traffic on the vehicle bus. Give it one or more 3-byte hex
ECU addresses, separated by commas:

```sh
clojure -M:run ssm-read 000022
clojure -M:run ssm-read FF6A04,FF6A05,FF6A06,FF6A07 /dev/cu.usbmodemTAlgJ3P81
```

From Clojure, pass the addresses as integers:

```clojure
(ssm/ssm-read path [0x000022])
```

It sends `A8 00` followed by each address to `7E0` and expects `E8` followed
by one byte per address from `7E8`. The `00` after `A8` asks for a single
read: SSM over CAN does not support the fast-poll mode that serial SSM has.
The result pairs each byte with its address, in this shape (values here are
from a test, not a vehicle):

```clojure
{:ok? true
 :protocol :iso15765
 :tx-id 2016
 :rx-id 2024
 :request {:bytes [168 0 0 0 34 255 106 4]
           :hex ["A8" "00" "00" "00" "22" "FF" "6A" "04"]}
 :response {:bytes [232 128 63] :hex ["E8" "80" "3F"] ...}
 :values [{:address 34 :address-hex "000022" :value 128}
          {:address 16738820 :address-hex "FF6A04" :value 63}]}
```

A reply with the wrong number of bytes returns `:unexpected-response`, and a
`7F` reply returns `:negative-response` with its `:nrc`, as with `obd`. It
takes the same `:tx-id`, `:rx-id`, `:baud`, and `:timeout-ms` options as
`obd-request`.

A request for one address is 5 bytes and fits in one CAN frame. Two or more
addresses need a multi-frame ISO15765 transmit, which this adapter has not
yet been seen to do. See
[Not yet confirmed](openport-protocol.md#not-yet-confirmed).

## Knock correction

| Value | Where it lives |
|---|---|
| Knock correction advance | SSM address `000022` on every SSM ECU. One byte; degrees = `(x - 128) / 2`. This is total knock correction, not FBKC and FLKC separately. |
| Feedback knock correction (FBKC) | An ECU RAM address that depends on the calibration (ROM) ID |
| Fine learning knock correction (FLKC) | An ECU RAM address that depends on the calibration (ROM) ID |

`000022` is the best first test because its address does not depend on the
ECU.

For FBKC and FLKC:

1. Read the ECU ID with `ssm-init`.
2. Look up that ID in RomRaider's logger definitions (`logger_*.xml`). FBKC
   and FLKC are extended parameters there, such as "Fine Learning Knock
   Correction (4-byte)*", with an address for each ECU ID. Addresses differ
   between ECUs with the same engine, so do not borrow another ECU's.
3. Read the four addresses starting at that address. These values are
   usually big-endian 32-bit floats in degrees:

```clojure
(let [{:keys [values]} (ssm/ssm-read path (map #(+ address %) (range 4)))]
  (Float/intBitsToFloat
   (unchecked-int (reduce (fn [acc b] (+ (* acc 256) b)) 0 (map :value values)))))
```

Check the logger definition's conversion for each parameter rather than
assuming a float.

The `logger_STD_EN_v290.xml` logger definition defines FBKC and FLKC for
2012-14 FB20 ECUs (ECU IDs `7334384107`, `73343C4207`, `73243C7507`, and
`8424385107`), but not for this project's car.

## This project's 2015 Impreza

Observed on 2026-10-26.

**Calibration ID.** `obd 0904` returns calibration ID `EA1T002C`, the same ID
SubaruEdit reports:

```
49 04 01 45 41 31 54 30 30 32 43 00 00 00 00 00 00 00 00
          E  A  1  T  0  0  2  C
```

`01` is the number of calibration IDs; the ID is ASCII padded with zeros to 16
bytes.

**No SSM reply.** `ssm-init` sent `AA` to `7E0` and got no reply within 5
seconds, neither `EA` nor a `7F` negative response. This happened with the
ignition on and the engine off (12.3V) and again with the engine running
(14.2V). `obd 0100` on the same channel was answered normally, so the ECU was
awake and the channel worked. RomRaider builds the same request (`7E0` then
`AA`, with no session setup before it), so the request format is not the
difference.

**Research.** Reported elsewhere, not tested here:

- A RomRaider forum thread on a 2015 NA Impreza reports SSM over CAN failing
  the same way. RomRaider's developer said in March 2024 that the logger
  cannot log that ECU over SSM on CAN or K-line, and guessed that it uses
  OBD-II mode `23` (UDS ReadMemoryByAddress) instead.
- RomRaider's "OBD CAN" mode reads that kind of ECU only through standard
  mode `01` PIDs and `09 04`. It finds the ECU ID by looking up the
  calibration ID in its ROM definitions. It reads no ECU memory, so it cannot
  log FBKC or FLKC.
- No ECU that EcuFlash flashes with the `subarubrz` method has logger
  parameters in `logger_STD_EN_v290.xml`: every 2012-18 BRZ, the 2015 US
  Legacy 2.0 (`EA1M702A`), and the 2016 Forester 2.5 (`EA1T400W`, the closest
  published relative of `EA1T002C`).
- There is no public ROM definition for `EA1T002C`, so its ECU ID and RAM
  addresses are unknown.

There are two leads, both UDS (ISO 14229) services rather than SSM:

- Service `22` (ReadDataByIdentifier) reads a value by a 2-byte data
  identifier (DID). `uds-read` sends it; see
  [Trying UDS service 22](#trying-uds-service-22).
- Service `23` (ReadMemoryByAddress), the guess from RomRaider's developer,
  is not built. It would need its own allowlist, with UDS `3D`
  (WriteMemoryByAddress) never sent.

The ECU answers service `22` in its default session, but the identifiers
that hold FBKC and FLKC, if any do, are unknown. For service `23`, whether
the ECU supports it, whether it needs a diagnostic session or security access
first, and which addresses to read are all unknown.

## Trying UDS service 22

`uds-read` sends traffic on the vehicle bus. Give it one or more hex data
identifiers, separated by commas; it sends one request for each:

```sh
clojure -M:run uds-read 1155
clojure -M:run uds-read 1155,1156,1157,0030 /dev/cu.usbmodemTAlgJ3P81
```

From Clojure, pass the identifier as an integer:

```clojure
(uds/read-did path 0x1155)
```

It sends `22` and the 2-byte DID to `7E0` and expects `62`, the DID echoed
back, and the data from `7E8`. The data is returned undecoded in `:data`,
because the encoding of each DID is unknown. A reply that echoes a different
DID returns `:unexpected-response`. A `7F` reply returns `:negative-response`
with its `:nrc`, named in `:nrc-name` when known. It takes the same options
as `obd-request`.

How to read the result:

| Result | Meaning |
|---|---|
| `:ok? true` with `:data` | The ECU has that DID. Its encoding still has to be worked out. |
| `:nrc-name :request-out-of-range` (`31`) | The ECU supports service `22` but not that DID |
| `:nrc-name :service-not-supported` (`11`) | The ECU does not support service `22` in its default session |
| `:nrc-name :security-access-denied` (`33`) or `:conditions-not-correct` (`22`) | The DID exists but needs security access or a different session, which are never sent |
| `:error :timeout` | No reply, as with SSM `AA` |

DIDs to try first (2026-10-29): `1155` (FBKC), `1156` (FLKC), `1157` (IAM),
and `0030` (knock sensor). These come from a Google AI overview that mixed up
SSM and service `22`, gave the SSM `000022` formula `(A - 128) / 2` for all
of them, and cited sources that do not contain them. Treat them as unverified
guesses.

**Result on this project's 2015 Impreza.** Observed on 2026-10-29, engine
running (14.3V). All four DIDs got `7F 22 31` (request out of range) from
`7E8`, each within about 40 ms. Unlike SSM `AA`, the ECU answers service `22`
in its default session, with no session change or security access, but none
of these four DIDs exist on it.

The standard UDS DID `F190` (VIN) returned `62 F1 90` and the 17-character
VIN in ASCII, as a multi-frame reply the adapter reassembled. So the ECU
implements at least the standard ISO 14229 identifiers, and positive replies
work end to end.

## Scanning for DIDs: `uds-scan`

`uds-scan` reads every DID in a range, one request each, and reports the
ones that are not out of range. Both ends are required, in hex and
inclusive, so a scan is always bounded:

```sh
clojure -M:run uds-scan 1000 10FF
```

It prints each DID found, and a line every 256 DIDs, to stderr as it goes,
then the result as EDN: `:found` (data, or the error for anything other than
out of range), `:out-of-range` (a count), and `:scanned`. When each request
opened its own channel, it took about 40 ms, so 256 DIDs took about 10
seconds and the full `0000`-`FFFF` range about 45 minutes. The scan now
shares one channel for every request, which should be faster, but that has
not been timed on the vehicle. It stops early with `:error :no-reply` once three
requests in a row get no reply, such as when the ignition is turned off.

**`1000`-`10FF` on this project's 2015 Impreza.** Observed on 2026-10-29,
engine running (14.2V), one snapshot. 55 DIDs returned data and the other 201
were out of range; no other negative responses. The eight 4-byte DIDs at
`1000`, `1020`, ... `10E0` are
[supported-DID bitmaps](#listing-supported-dids-uds-supported). What a drive
showed about the others is in [What the DIDs hold](#what-the-dids-hold).

**Idle trial with `record-dids`.** Observed on 2026-10-29: 10 seconds of
`record-dids ... scanned` at a warm idle (about 725 rpm, coolant 94 °C,
timing advance 10-12.5°). All 52 DIDs replied over the raw CAN channel, each
read 6 or 7 times, with no negative responses and no other replies from
`7E8`. Every request got a reply: 334 PID and 333 DID replies to 667
requests. These changed during the 10 seconds:

| DID | Values | Note (inferences from one idle) |
|---|---|---|
| `10DE` | `6E`-`74` | Moved while timing advance moved; `x / 2 - 45` gives 10-13°, which may be coincidence |
| `10A1` | `38`-`3A` | Rising; `37` in the scan earlier that day, so possibly a temperature |
| `10BE` | `80`, `7F` | A knock correction candidate; `7F` would be -0.5°, but it may as well be a trim |
| `10B6` | `8F`, `90` | Near `80`, like a trim |
| `10B9` | `15`, `16` | Its pair `10B8` held at `16` |

`103D` (`2A` to `2D`) and `10B2` (`42` to `43`) also differed from the scan
earlier that day but held steady during the trial. The rest, including
`10A5` and `10BA` at `80`, held steady. Knock correction would be 0 at idle
anyway, so a drive with load is needed to tell the candidates apart.

```
1000 FF C0 00 0D           1001 04 00                 1002 00 32
1003 00 0A                 1004 00 0A                 1005 03 FE
1006 00 1E                 1007 04 00                 1008 00 05
1009 00 05                 100A 00 FF                 101D 00
101E 6E 98 00 00 0A 6A     1020 50 00 00 19           1022 00
1024 00 00 00              103C 00 08                 103D 00 2A
1040 00 00 00 01           1060 A0 00 00 01           1061 00 00 00 00 00
1063 00 00 00 00 00        1080 01 80 00 01           1088 00
1089 FF                    10A0 AC 04 DF D5           10A1 37
10A3 09                    10A5 80                    10A6 00
10AE 00                    10B1 00                    10B2 42
10B4 32                    10B5 32                    10B6 91
10B7 8D                    10B8 16                    10B9 16
10BA 80                    10BC 32                    10BE 80
10C0 80 00 00 07           10C1 40                    10DE 72
10DF 43                    10E0 28 1F 80 01           10E3 00
10E5 FF FF                 10EC 32                    10ED 32
10EE 73                    10EF 73                    10F0 11
10F1 11
```

## Listing supported DIDs: `uds-supported`

`uds-supported` sends traffic on the vehicle bus. It lists the DIDs the ECU
supports by reading its supported-DID bitmaps, one request for every 32 DIDs,
instead of trying each DID:

```sh
clojure -M:run uds-supported
clojure -M:run uds-supported 1000 /dev/cu.usbmodemTAlgJ3P81
```

The bitmaps use the layout of OBD mode 01 PIDs `00`, `20`, and so on. The DID
at a bitmap address, such as `10A0`, returns 4 bytes. Bit 7 of the first byte
says whether `10A1` is supported, and so on through the last bit, which says
whether `10C0`, the next bitmap, is. `uds-supported` starts at `1000` (or the
given DID), follows the chain, and stops at the first bitmap whose last bit
is clear. It returns the bitmaps in `:bitmaps` and every other supported DID
in `:dids` and `:dids-hex`. A failed read or a bitmap that is not 4 bytes
stops it with `:ok? false` and `:failed-at`, keeping what it found.

**Evidence on this project's 2015 Impreza.** The layout is inferred, not
documented. Observed on 2026-10-29, from the `uds-scan` of `1000`-`10FF`:
the eight 4-byte DIDs at `1000`, `1020`, ... `10E0` held the same value for
the whole drive below, and their bits mark exactly the 54 other DIDs the scan
found in `1001`-`10FF`, with none missing or extra. For example, `10A0`
returns `AC 04 DF D5`, whose bits mark `10A1`, `10A3`, `10A5`, `10A6`, `10AE`,
`10B1`, `10B2`, `10B4`-`10BA`, `10BC`, `10BE`, and `10C0`. The last bit of
`10E0` (`28 1F 80 01`) is set, so `1100` should be another bitmap and there
are supported DIDs past `10FF`. `uds-supported` has not yet been run on the
car. Whether other chains start elsewhere, such as at `0000` or `F400`, is
unknown.

## What the DIDs hold

Observed on 2026-10-29 in the
[`2026-10-29-did-drive`](recordings.md#2026-10-29-did-drive) recording:
`record-dids ... scanned`, 4.9 minutes of town driving with a warm engine
(coolant 92-99 °C), 0-82 km/h, up to 5,040 rpm and 100% load, and timing
advance from -12° to 46.5°. Every one of the 19,431 requests got a reply;
each DID was read about 187 times, about every 1.6 seconds. Each DID was
compared with the nearest PID reply (within 0.25 s, or 3 s for slow PIDs).

These match a PID closely. The scales are fitted from this drive, not
documented, and [held on a second drive](#a-second-drive-2026-10-30), which
also added `10B2` and a better fit for `10A1`. `uds/did-signals` holds them,
and the dashboard shows each beside the value it was matched against:

| DID | Matches | Fit | r |
|---|---|---|---|
| `10DF` | Throttle position (PID `11`) | % = 0.8 × x - 40 | 0.999 |
| `10BA` | Wideband sensor current (PID `34`) | mA = (x - 128) / 8 | 0.991 |
| `10A6` | Relative accelerator pedal position (PID `5A`) | % = 0.178 × x | 0.984 |
| `10BE` | Measured lambda (PID `34`) | lambda = x / 128; 177 in fuel cut, about 110 at full throttle | 0.977 |
| `10A3` | Air per revolution (MAF / rpm) | g/rev = 0.019 × x, near 0 at x = 0 | 0.94 |
| `10A1` | Mass airflow (PID `10`) | g/s = (0.0177 × x + 0.186)⁴, from the second drive | 0.995 against MAF^¼ |
| `10B2` | ECU voltage (PID `42`) | V = 0.05 × x + 10.73 once the voltage settles, from the second drive | 0.92 |

2026-11-10: two of these are named differently in lists other people have
published, and the published names fit better. `10A3` is the injector
pulse width and `10B2` the alternator's duty; see
[Published names](#published-names-2026-11-10).

Two of these also appear in the car's own traffic (found 2026-11-10; see
[broadcast CAN traffic](can-broadcast.md)): `10A6` is the same byte as
`140` byte 0, the pedal, and `10A3` follows `141`'s engine torque
(r = 0.98).

These change, but match no PID. What they are is unknown; the notes are
inferences:

| DIDs | Behavior |
|---|---|
| `10EC`, `10ED` | A matched pair. 50 at idle and in fuel cut, 70-77 under load. Slope 1.0 against timing advance, but loosely (r 0.82). Possibly cam timing for each bank. |
| `10EE`, `10EF`; `10F0`, `10F1` | Matched pairs that loosely follow load and timing advance (r 0.64-0.72) |
| `10B6`, `10B7`; `10B8`, `10B9` | Matched pairs, about 145 and 22 with fuel on. They drop to about 13 and 0 the moment fuel cut starts and recover as soon as it ends, too fast to be the rear oxygen sensor. |
| `10A5` | 126-128 at light load. Above about 50% load it moves, mostly up: as high as 149 (at 100% load, 3,171 rpm), and down to 122 once. |
| `10DE` | Follows load and timing advance (r 0.66) |
| `10AE`, `10B1`, `10B4`, `10B5` | Vary under load; weak matches |
| `1088`, `1089` | Opposite flags, `00` and `FF`. They swapped once, at 122 s, during a full-load pull at about 4,400 rpm. The second drive suggests they mark the first full throttle since the engine started. |
| `10BC`, `10C1` | Drift by a count or two |

2026-11-10: most of these now have names, from published lists; see
[Published names](#published-names-2026-11-10).

The rest held one value for the whole drive: the bitmaps, `1001`-`100A`,
`101D`, `1022`, `1024`, `103C`, `103D`, `10E3`, and `10E5`. `103D` was `2A`
in the scan and `2D` from the idle trial on. `10B2` drifted between 66 and 68.

**Knock correction is not identified.** Knock correction only ever removes
timing, so it reads 0 or less. `10A5` is the only DID that moves only under
load, but it rises above its resting value, and with RPM and load accounted
for it barely tracks timing (r -0.25 over 46 samples above 40% load). It may
be something else, such as an enrichment. A knock value that stayed at 0
because the engine never knocked would look like `101D`, `1022`, or `10E3`,
which held at `00`, so those are not ruled out. It may also be past `10FF`,
where the bitmaps say more DIDs exist.

### A second drive (2026-10-30)

Observed on 2026-10-30 in the
[`2026-10-30-did-drive`](recordings.md#2026-10-30-did-drive) recording, made
the same way: 14.4 minutes of town and highway driving, 0-123 km/h, up to
5,463 rpm and 100% load, timing advance from -12° to 49°, coolant 84-96 °C,
and oil warming from 74 to 98 °C. Each DID was read about 553 times.

**The fits held.** Applied unchanged, the first drive's scales matched this
drive: throttle (`10DF`) to a median of 0.1%, measured lambda (`10BE`) to
0.003, wideband current (`10BA`) to 0.02 mA, with r from 0.94 to 0.997.
Refitting changed each slope by 1-5%.

**`10A1` is airflow on a fourth-root scale.** It rises with mass airflow in
the same order every time (rank correlation 0.99), but not in a straight
line. MAF^¼ fits it with r 0.995, and the fit was the same on both drives:
MAF^¼ = 0.0177 × x + 0.186 on the first and 0.0177 × x + 0.185 on this one.
Fitted on the first drive, it predicts this drive's MAF to a median of 3.7%.
A hot-film MAF sensor's output rises with about the fourth root of the flow,
so `10A1` is probably the sensor's own output, before the ECU converts it.
That is an inference: the sensor's real voltage is unknown.

**`10B2` follows the charging system.** In the first drive it barely moved
(66-68) and ECU voltage stayed at 13.4-14.4 V. In this one it ranged from 28
to 69 and voltage from 11.7 to 14.5 V, and the two correlate (r 0.91 at the
nearest sample, falling with lag). Where `10B2` had been steady for 4
seconds, V = 0.05 × x + 10.73 (r 0.92, residual 0.25 V). It sits at three
levels:

| `10B2` | Voltage | When |
|---|---|---|
| 68-69 | about 14.2 V | Idle, most deceleration, and some driving |
| 50-53 | about 13.3 V | Deceleration and light driving |
| 28-31 | about 12.2 V | Under power; only twice in 85 readings was the load 0 |

One value covers a wide range of measured voltage (68 at 13.3-14.5 V), so it
is not a measurement. It looks like a charging voltage target, which the ECU
lowers under acceleration to take load off the engine, as charge control
does. Why it stayed high through the first drive is unknown; the battery may
have needed charging.

**`1088` and `1089` mark the first full throttle.** In both drives they
swapped once, about a second after throttle first reached 80%, the most this
car's throttle reads, and held. Before that, throttle had reached 47% on the
first drive and 33% on this one. Engine load had already reached 100% within
20 seconds of starting on both drives, so the trigger is the throttle, not
the load. What the ECU uses the flag for is unknown.

**`103D` changes only between drives.** It held one value through every
drive: `2A` in the first scan, `2D` from the idle trial on, and `2F` in this
drive. `103C` changed once, from 8 to 9, 11 minutes in.

**Knock correction is still not identified.** `101D`, `1022`, and `10E3`
held at `00` again, now with timing advance up to 49° and full-load pulls
past 5,000 rpm. `10A5` again rose under load (126-152).

### Published names (2026-11-10)

Two lists other people have published name most of the DIDs above. Neither
is for this ECU, so each name was taken as a guess and tested against the
two DID drives, with the car's own [CAN traffic](can-broadcast.md) for
torque, fuel flow, and fuel cut, which arrives fast enough to line up with
each reply.

- **Diesel Crew:** the Subaru Diesel Crew's mode 22 table, mirrored in
  [ParsePID](https://github.com/giotec/ParsePID)
  (`data/Subaru_mode22_def.csv`). It gives each DID's name, formula, and
  the SSM address it mirrors. Its README has the supported-DID bitmaps of a
  2014 Forester FB25 (`EA1M220F`), which supports almost the same DIDs as
  this car.
- **OBDb:** the community signal sets for the
  [Impreza](https://github.com/OBDb/Subaru-Impreza) and its relatives.
  A few entries have replies captured from a 2015 Impreza (`10A3`, `10EC`,
  `10ED`); most are one list copied across models.

| DID | Published as | Scale | On this car |
|---|---|---|---|
| `10A3` | Injector #1 pulse width | x × 0.256 ms | 1.5-14.8 ms, and 0 in fuel cut. The fuel in each injection, from `360`'s fuel flow, is 3.16 mg/ms × this − 1.73 in the first drive and 3.12 × this − 1.80 in the second (r = 0.999 and 0.998), against r = 0.975 and 0.985 for air per revolution |
| `10A5` | Learned ignition timing: "advance or retard amount when knocking has occurred" | (x − 128) ÷ 2 ° | 0° at idle and light load, −3° to +12° under load |
| `10AE` | Canister purge valve duty | x ÷ 2.55 % | 0-100%; 0 at idle |
| `10B1` | EGR steps | x | 0-50. 0 below about 36 kPa of manifold pressure and near full throttle, 13-42 between. Its value in one drive is given by the other's at the same RPM and torque (r² 0.93) |
| `10B2` | Alternator duty | x % | See below |
| `10B4`, `10B5` | Intake cam advance, right and left | x − 50 ° | −28° to +37°. 0° at idle and in fuel cut, −9° to −18° at 20-80 Nm, +26° to +30° above 140 Nm |
| `10B6`, `10B7` | Oil control valve duty, right and left | x ÷ 2.55 % | 57% at idle, 5% in fuel cut, up to 62% |
| `10DE` | Throttle motor duty | (x − 128) ÷ 1.28 % | −33% to +33%; −12% to −16% at idle, positive on the move |
| `10EC`, `10ED` | Exhaust cam retard, right and left | x − 50 ° | −1° to +32°. 0° at idle and in fuel cut, about 20° at 20-140 Nm, 25° above |
| `10EE`, `10EF` | Exhaust oil control valve duty, right and left | x ÷ 2.55 % | 45% at idle and in fuel cut, up to 61% |

The cam angles reading exactly 0° at idle and in fuel cut, and the valve
duties holding still there, is the best evidence for the offsets. The
scales themselves are the lists'. `uds/did-signals` decodes all of these,
marked `:published? true`, and the dashboard shows them tagged published.
Which bank is "right" isn't known.

**`10A3` is not air per revolution.** It was fitted above as 0.019 g/rev
per count, because at a steady mixture the injector's opening time follows
the air. The fuel fits it more closely, and the line misses zero by the
injector's dead time: 0.55-0.58 ms. Its slope is the injector's flow,
3.1-3.2 g/s, about 250 cc/min. Both are inferences from `360`'s fuel-flow
scale, which is itself fitted.

**`10B2` is the alternator's duty, not a voltage.** The three levels found
in the second drive are duties of about 30%, 51%, and 68%. The
[battery current](can-broadcast.md#battery-current-and-temperature-in-361)
in the car's own traffic shows what they do: with the duty under 40% the
battery is discharging, at 40-59% it is charging, and at 68% it is near
balance. The fit of ECU voltage to it (0.05 × x + 10.73) still describes
where the voltage settles.

**Knock.** `10A5` is the nearest thing yet to the knock value this page set
out to find, and its formula is the one SSM uses for knock correction at
`000022`. It moves the wrong way for a correction that only removes
timing, which is what the earlier notes held against it. As learned
timing that is expected: on an engine that isn't knocking the ECU adds
advance. FBKC, FLKC, and IAM are in neither list.

Inferred from the lists' order, which follows SSM's, and not stated by
either. `uds/did-notes` holds these:

| DID | Probably | On this car |
|---|---|---|
| `10B8`, `10B9` | Intake oil control valve current, x × 32 mA | 704 mA at idle, 0 in fuel cut; it follows `10B6`'s duty (r = 0.99 and 0.64 in the two drives). The factory manual for the XV of this generation gives 550-850 mA |
| `10F0`, `10F1` | Exhaust oil control valve current, x × 32 mA | 544 mA at idle, up to 800; it follows `10EE`'s duty (r = 0.97) |
| `10BC` | Wideband sensor resistance, x ohms | Holds at 49-51; the manual says about 32, so this is doubtful |

Neither list names `103C`, `103D`, `1088`, `1089`, or `10C1`. OBDb has
`10E3` as the cruise control's memorised speed, which fits it holding at
`00` with the cruise control unused, and `10E5` as an odometer in 2 km
steps, which doesn't fit: it reads `FF FF` here.

### DIDs past `10FF`

The lists also name DIDs this car hasn't been asked for. Its bitmaps say
more exist past `10FF`. All are service `22` reads; none has been tried
here, and the names are the lists', untested:

| DID | Published as |
|---|---|
| `110B`-`110E` | Roughness monitor, cylinders 1-4 |
| `1121` | Target engine speed, 16 bits ÷ 4 |
| `1135` | Battery current, x − 128 A |
| `1136` | Battery temperature, x − 40 °C |
| `1137` | Alternator control mode (0 high, 1 extra high, 2 low, 3 mid) |
| `11D0` | "Knocking signal", in degrees |
| `122F`-`124C` | Switches as `00` or `FF`: clutch, stop light, the cruise control's buttons, oil level |
| `1275`-`1279` | Throttle motor voltage; throttle and pedal sensor voltages |
| `128F`-`1292` | Intake and exhaust cam target angles, x − 50 ° |

`1135`-`1137` would settle the scale of the battery current found in
`361`. Run `uds-supported 1100` or `uds-scan 1100 12FF` with the engine
running to see which the ECU has.

## Safety

The only SSM commands built are `AA` (`ssm-init-command`) and `A8`
(`ssm-read-addresses-command`), and neither caller can supply a command byte.
The only UDS service built is `22` (`read-data-by-identifier-service`), and
`read-did` takes only a DID. Never send UDS `2E` (WriteDataByIdentifier),
`3D` (WriteMemoryByAddress), `31` (RoutineControl), `11` (ECUReset), `10`
(DiagnosticSessionControl), or `27` (SecurityAccess).
SSM also has commands that write ECU memory (`B0` and `B8`). Never send them:
RomRaider resets the ECU by writing `40` to `000060` with `B8`.
Forum notes say restricted addresses read back as `FF` rather than failing.
