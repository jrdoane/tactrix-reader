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
out of range), `:out-of-range` (a count), and `:scanned`. Each request takes
about 40 ms, so 256 DIDs take about 10 seconds and the full `0000`-`FFFF`
range about 45 minutes. It stops early with `:error :no-reply` once three
requests in a row get no reply, such as when the ignition is turned off.

**`1000`-`10FF` on this project's 2015 Impreza.** Observed on 2026-10-29,
engine running (14.2V), one snapshot. 55 DIDs returned data and the other 201
were out of range; no other negative responses. What each one means is not
yet known. Single bytes at `80` (`10A5`, `10BA`, `10BE`) would read as 0 with
the `(x - 128) / 2` knock correction formula, and the matching pairs
(`10B4`/`10B5`, `10B8`/`10B9`, `10EC`/`10ED`, `10EE`/`10EF`, `10F0`/`10F1`)
could be per-bank values on the boxer engine, but both are inferences from
one snapshot. To find out, record them alongside the PIDs while driving with
`record-dids` (see [Recording UDS data identifiers](recordings.md#recording-uds-data-identifiers)).

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
