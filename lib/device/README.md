# Device library

This library starts with host/device proof-of-life checks for a Tactrix
OpenPort 2.0, or any similar USB/J2534 adapter that exposes a macOS usbmodem
device.

The first milestone is deliberately below vehicle protocol level. With the
adapter connected to the computer but not a vehicle, we can verify that macOS
can enumerate the adapter and that this process can open the exposed device
path.

## Smoke test

From this directory:

```sh
clojure -M:run scan
```

Expected shape when the OpenPort is connected:

```clojure
{:devices
 [{:vendor "Tactrix"
   :product "OpenPort 2_0"
   :serial "TAlgJ3P8"
   :vendor-id 1027
   :product-id 52301
   :paths ["/dev/cu.usbmodemTAlgJ3P81"]
   :path "/dev/cu.usbmodemTAlgJ3P81"
   :source :ioreg}]}
```

Then attempt an open/close proof:

```sh
clojure -M:run open
```

Success means the host can see and open the adapter:

```clojure
{:ok? true
 :path "/dev/cu.usbmodemTAlgJ3P81"
 :opened? true}
```

If the device is unplugged, busy, or blocked by permissions, the command returns
EDN with `:ok? false` and a specific `:error` keyword instead of throwing.

## Vehicle status

The macOS backend proves host/device status and reads vehicle battery voltage
from the adapter. It does not send CAN/K-line traffic.

```sh
clojure -M:run status
```

With the adapter connected to the computer but not a vehicle, pin 16 floats near
0V, so vehicle power reads as absent:

```clojure
{:backend :mac-serial
 :host/device-present? true
 :host/openable? true
 :vehicle/power {:state :absent
                 :volts 0.152}
 :vehicle/ecu {:state :unsupported}
 :confidence :medium}
```

The voltage policy is:

- `>= 9.0V`: vehicle power present
- `< 3.0V`: vehicle power absent
- `3.0V` to `< 9.0V`: unknown

You can read the voltage directly:

```sh
clojure -M:run vbat
```

```clojure
{:ok? true
 :backend :mac-serial
 :path "/dev/cu.usbmodemTAlgJ3P81"
 :pin 16
 :millivolts 152
 :volts 0.152
 :state :absent
 :response "arr 16 152\r\n"}
```

This sends the adapter-local `atr 16` command (see below). If the adapter
replies with an error or something unexpected, the result has `:ok? false`,
`:state :unknown`, and an `:error` keyword. That is different from proving
vehicle power is absent.

## OpenPort serial protocol

Discovered by probing an OpenPort 2.0 (firmware 1.17.4877) that was connected
to the computer but not a vehicle. Commands are ASCII lines terminated with
`\r\n`; replies are ASCII lines that start with `ar`. The commands line up with
J2534 PassThru functions, and error codes are J2534 status codes.

Channel IDs are J2534 protocol IDs (3 = ISO9141, 5 = CAN, 6 = ISO15765). Open
channels and filters live in the adapter and survive closing and reopening the
serial port. Filter IDs count up across channels.

| Request | Reply | Meaning |
|---|---|---|
| `ati` | `ari main code version : 1.17.4877` | Firmware version |
| `ata` | `aro` | OK, likely PassThruOpen |
| `atz` | `aro` | OK, likely reset or PassThruClose |
| `atr <pin>` | `arr <pin> <millivolts>` | Pin voltage |
| `ato<proto> <flags> <baud> [tag]` | `aro` | PassThruConnect |
| `atc<chan>` | `aro` | PassThruDisconnect; also `aro` if already closed |
| `atg<chan> <param> [tag]` | `arg<chan> <param> <value> <tag>` | GET_CONFIG |
| `ats<chan> <param> <value>` | `aro` | SET_CONFIG |
| `att<chan> <len> <flags>` + `len` raw bytes | `aro` | PassThruWriteMsgs |
| `atf<chan> <type> <flags> <len> [tag]` + mask + pattern | `arf<chan> <filter-id> <tag>` | PassThruStartMsgFilter |
| `atk<chan> <filter-id>` | `aro` | PassThruStopMsgFilter |
| any | `are <code>` | Error |

Examples:

- `ato5 0 500000` opens CAN at 500 kbps. Opening it again returns `are 20`.
- `atg5 1` returns `arg5 1 500000 0` (DATA_RATE). `ats5 3 1` turns on LOOPBACK.
- `atf3 1 0 1` followed by bytes `00 00` adds a pass-all filter (type 1 = PASS,
  1-byte mask `00`, 1-byte pattern `00`) and returns `arf3 <id> 0`.

Pin reads seen so far: `atr 16` (OBD-II battery +12V) returns about 150-175
with no vehicle and 12591 on a vehicle with the engine off, `atr 12` returns 0,
and `atr 17` returns about 5533, likely an internal supply. Other pins return
`are 19`.

### Received messages

Received messages are binary, not text lines, and arrive unprompted on the
serial stream between command replies:

```
"ar" <channel digit> <length u8> <status u8> <timestamp u32> <CAN ID u32> <data>
```

`length` counts the bytes after itself (1 + 4 + 4 + data). Integers are big
endian. The timestamp is the adapter's clock in microseconds.

For example, `ar5` `11` `00` `0A57C6EF` `00000370` `00 00 20 05 FC 07 00 00` is
an 8-byte CAN frame with ID `0x370`.

Status values seen:

| Status | Seen on | Meaning |
|---|---|---|
| `00` | CAN | Received frame, or a copy of a frame this adapter sent |
| `20` | CAN with LOOPBACK | ID 0, no data, right after the sent frame's copy; likely transmit done |

On CAN, the adapter delivers a copy of each frame it sends whenever the
channel's filters pass that ID, even with LOOPBACK off. LOOPBACK adds the `20`
frame.
| `10` | ISO15765 | Sent ID, no data; likely transmit done |
| `80` | ISO15765 | Reply ID, no data; a multi-frame reply has started |
| `40` | ISO15765 with padding | Complete, reassembled message |

These are not standard J2534 RxStatus bit values, so treat their meanings as
observations.

### Vehicle results

Tested on a 2015 Subaru with the ignition on and the engine off. The OBD port
carries live CAN traffic at 500 kbps, about 880 frames per second, so a CAN
channel with a pass-all filter (`atf5 1 0 4` + 4-byte mask `00000000` + 4-byte
pattern `00000000`) receives frames without sending anything.

OBD-II requests that worked:

- Raw CAN: `att5 12 0` + `000007DF 02 01 00 00 00 00 00 00` (mode 01 PID 00 to
  the functional address). The engine ECU replies on `7E8` with
  `06 41 00 BE 3F A8 13 00` about 1 ms later.
- ISO15765: a flow-control filter is `atf6 3 64 4` + 4-byte mask `FFFFFFFF`,
  4-byte pattern `000007E8`, and 4-byte flow-control ID `000007E0`. Then
  `att6 6 64` + `000007E0 01 00` gets `41 00 BE 3F A8 13`, and
  `att6 6 64` + `000007E0 09 02` gets the full 20-byte VIN reply as one
  reassembled message.

Flags 64 is `ISO15765_FRAME_PAD`. Without it, ISO15765 requests go out as short
frames and this ECU ignores them.

Error codes seen:

| Code | J2534 status |
|---|---|
| 1 | `ERR_NOT_SUPPORTED` (e.g. unknown config param) |
| 2 | `ERR_INVALID_CHANNEL_ID` |
| 3 | `ERR_INVALID_PROTOCOL_ID` |
| 7 | `ERR_FAILED` (also missing or malformed arguments) |
| 9 | `ERR_TIMEOUT` |
| 10 | `ERR_INVALID_MSG` |
| 13 | `ERR_INVALID_MSG_ID` |
| 19 | `ERR_PIN_INVALID` |
| 20 | `ERR_CHANNEL_IN_USE` |
| 22 | `ERR_INVALID_FILTER_ID` |

Partly understood:

- `atw<chan> <addr>` blocks for about 2.5 seconds, then returns `are 7` with no
  vehicle. That matches a 5-baud init (FIVE_BAUD_INIT) with no ECU answering.
- `atn<chan> <id>` returns `are 13`, so it is likely PassThruStopPeriodicMsg.
- `atp<chan>` and `aty<chan>` return `are 10`, so they expect a message. They
  are likely PassThruStartPeriodicMsg and FAST_INIT.
- `atx<chan> <id>` returns `are 7 <id>`. It echoes the ID like an ioctl, but
  its arguments are unknown.
- `atl<chan>` returns `aro`. It does not clear filters; its meaning is unknown.
- `atm<chan> ...` returns `are 7`; unknown.
- `atb`, `atd`, `ate`, `ath`, `atj`, `atq`, and `atu` never reply, with or
  without a channel.

Gotchas:

- `att` and `atf` read exactly the declared number of payload bytes. Extra
  bytes are parsed as the start of the next command, which then fails silently.
- With no payload, `att` waits about 0.5 seconds and returns `are 9`.
- A correctly sized CAN `att` returns `are 9` after about 1 second because no
  node on the bus acknowledges the frame.

Not yet confirmed:

- K-line receive. With no vehicle, a K-line `att` with LOOPBACK on and a
  pass-all filter returned `aro` but produced no echo, probably because the
  K-line transceiver is powered from pin 16. Not yet retried on a vehicle.
- `atv` with arguments. It may set programming voltage on a pin, so do not
  send it until its arguments are understood.

## OBD-II requests

`obd` sends one OBD-II request to the engine ECU over ISO15765 and prints the
reply. Unlike the commands above, this sends traffic on the vehicle bus:

```sh
clojure -M:run obd 0100
clojure -M:run obd 0902 /dev/cu.usbmodemTAlgJ3P81
```

```clojure
{:ok? true
 :protocol :iso15765
 :tx-id 2016
 :rx-id 2024
 :request {:bytes [1 0] :hex ["01" "00"]}
 :response {:bytes [65 0 190 63 168 19]
            :hex ["41" "00" "BE" "3F" "A8" "13"]
            :status 64
            :timestamp-us 612340701}}
```

The request is the service byte and its parameters. Only read-only services
are sent (01, 02, 03, 05, 06, 07, 09, 0A); service 04 (clear DTCs) and 08
(control on-board systems) return `:service-not-read-only` without opening the
device.

Each request opens ISO15765 at 500 kbps with padding, adds a flow-control
filter for `7E0`/`7E8`, sends the request, waits for the reply, and closes the
channel. The adapter reassembles multi-frame replies such as the VIN. A
negative response returns `:negative-response` with its `:nrc`; NRC `0x78`
(response pending) is waited through. `obd-request` takes `:tx-id`, `:rx-id`,
`:baud`, and `:timeout-ms` options for other ECUs and buses.

### Decoded PIDs

`pid` reads one mode 01 PID and decodes it with the SAE J1979 formula. Give it
a name or a hex PID number:

```sh
clojure -M:run pid engine-rpm
clojure -M:run pid 05
```

```clojure
{:ok? true
 :pid 12
 :name :engine-rpm
 :value 1198.5
 :unit :rpm
 ...}
```

The rest of the `obd` result (request, raw response bytes) is included.
Decoded PIDs:

| PID | Name | Unit |
|---|---|---|
| 00, 20, 40, 60 | `supported-pids-01-20`, `-21-40`, `-41-60`, `-61-80` | list of PID numbers, also as hex strings in `:value-hex` |
| 01 | `monitor-status` | map: `:mil-on?`, `:dtc-count`, `:ignition`, `:monitors` |
| 03 | `fuel-system-status` | map: `:system-1`, `:system-2` (e.g. `:closed-loop`) |
| 04 | `engine-load` | percent |
| 05 | `coolant-temp` | Celsius |
| 06, 07 | `short-term-fuel-trim-bank-1`, `long-term-fuel-trim-bank-1` | percent |
| 0B | `intake-manifold-pressure` | kPa |
| 0C | `engine-rpm` | rpm |
| 0D | `vehicle-speed` | km/h |
| 0E | `timing-advance` | degrees before TDC |
| 0F | `intake-air-temp` | Celsius |
| 10 | `maf-air-flow` | g/s |
| 11 | `throttle-position` | percent |
| 13 | `oxygen-sensors-present` | list such as `:bank-1-sensor-1` |
| 15 | `oxygen-sensor-bank-1-sensor-2` | map: `:volts`, `:fuel-trim-percent` (nil if unused) |
| 1C | `obd-standard` | keyword such as `:obd-and-obd-ii` |
| 1F | `run-time` | seconds since engine start |
| 21 | `distance-with-mil-on` | km |
| 24 | `wideband-oxygen-sensor-1-voltage` | map: `:lambda`, `:volts` |
| 2C | `commanded-egr` | percent |
| 2D | `egr-error` | percent |
| 2E | `commanded-evap-purge` | percent |
| 2F | `fuel-level` | percent |
| 30 | `warm-ups-since-codes-cleared` | count |
| 31 | `distance-since-codes-cleared` | km |
| 33 | `barometric-pressure` | kPa |
| 34 | `wideband-oxygen-sensor-1-current` | map: `:lambda`, `:milliamps` |
| 3C-3F | `catalyst-temp-bank-1-sensor-1`, `-bank-2-sensor-1`, `-bank-1-sensor-2`, `-bank-2-sensor-2` | Celsius |
| 41 | `monitor-status-this-drive-cycle` | map: `:ignition`, `:monitors` |
| 42 | `control-module-voltage` | volts |
| 43 | `absolute-load` | percent |
| 44 | `commanded-lambda` | lambda (1.0 is stoichiometric) |
| 45 | `relative-throttle-position` | percent |
| 46 | `ambient-air-temp` | Celsius |
| 47, 48 | `absolute-throttle-position-b`, `-c` | percent |
| 49, 4A, 4B | `accelerator-pedal-position-d`, `-e`, `-f` | percent |
| 4C | `commanded-throttle-actuator` | percent |
| 4D | `time-with-mil-on` | minutes |
| 4E | `time-since-codes-cleared` | minutes |
| 51 | `fuel-type` | keyword such as `:gasoline` |
| 53 | `evap-vapor-pressure-absolute` | kPa |
| 5A | `relative-accelerator-pedal-position` | percent |
| 5C | `oil-temp` | Celsius |
| 65 | `auxiliary-io-status` | map of reported inputs, e.g. `:manual-transmission-in-gear?` (false in neutral) |

`:monitors` lists only the readiness monitors the vehicle has, each as
`:complete` or `:incomplete`. MIL is the check-engine light. Fuel system,
fuel type, and OBD standard codes the decoder does not recognise are returned
as numbers.

Other PIDs return `:unknown-pid` with the raw bytes; use `obd` for those. Not
every vehicle supports every PID; read `pid 00` to see which it does.

`pids` reads everything at once. It reads the supported-PID bitmaps (following
each one's next-range bit), then reads only the decoded PIDs the vehicle
supports:

```sh
clojure -M:run pids
```

```clojure
{:ok? true
 :supported-hex ["01" "03" "04" ...]
 :values [{:pid 4 :name :engine-load :value 23.5 :unit :percent}
          {:pid 5 :name :coolant-temp :value 77 :unit :celsius}
          ...]
 :errors []
 :not-supported []}
```

PIDs that fail to read are listed in `:errors` and make `:ok?` false. Decoded
PIDs the vehicle does not support are listed in `:not-supported` and are not
requested.

## Recordings

`record` captures raw CAN traffic for offline work while polling a set of mode
01 PIDs (RPM, speed, load, temperatures, trims, lambda, throttle and pedal,
voltage, fuel level). It records for 60 seconds unless given a number of
seconds:

```sh
clojure -M:run record recordings/my-drive
clojure -M:run record recordings/my-drive 120 /dev/cu.usbmodemTAlgJ3P81
```

It opens CAN at 500 kbps with a pass-all filter and polls with the same
schedule as the live source (below): one read-only request to `7E0` every
15 ms, so fast-changing PIDs are sampled about every 135 ms. Calling `record`
with `:pids []` only listens.

The folder gets:

| File | Contents |
|---|---|
| `raw.bin` | Exact bytes the adapter sent, including command replies |
| `meta.edn` | Firmware, bus settings, polling schedule, and counts |
| `frames.csv` | Every CAN frame, including copies of the polling requests |
| `pids.csv` | Decoded PID replies |

Both CSVs use `t_s` (seconds since the first frame) and the adapter's
microsecond timestamp, so PID values line up with bus traffic. They are derived
from `raw.bin`; `frames.csv` is large and not committed. Regenerate both with:

```sh
clojure -M:run export-recording recordings/2026-10-24-idle
```

`recordings/2026-10-24-idle` is one minute of a warm 2015 Subaru Impreza
(manual) idling in neutral: about 57,600 frames and 2,400 PID samples. It was
made with an earlier schedule that polled 20 PIDs every 25 ms in turn, so each
value is sampled only every 500 ms.

## Live and replay sources

`start-live-source!` and `start-replay-source!` run on a background thread and
call an `:emit` function with the same events, so consumers such as the engine
server cannot tell them apart:

- `{:type :sample :pid :name :value :unit :timestamp-us}` for each PID reading
- `{:type :status :source :live|:replay :state :running|:stopped|:failed ...}`

Each returns a map whose `:stop!` function stops it and waits for it to finish.

The live source keeps one adapter session open on the raw CAN channel and
sends a read-only request every 15 ms (`default-poll-interval-ms`). The
schedule (`default-poll-schedule`) sends all fast-changing PIDs (RPM, speed,
load, throttle, pedal, MAF, manifold pressure, timing), then one slow PID
(temperatures, voltage, fuel level, trims, lambda, check-engine status), and
repeats. Each fast PID is sampled about every 135 ms and each slow PID about
every 1.35 seconds. `record` uses the same session code and schedule.

The replay source plays back a recording's PID samples paced by their
timestamps, looping by default and shifting timestamps so they keep
increasing.

## Direct adapter backend

This project does not depend on a vendor J2534 library. The code we own talks to
the adapter through the device path exposed by the operating system. The current
direct backend can enumerate the adapter, open it, passively observe bytes, and
run explicit request/response probes.

```sh
clojure -M:run direct-info
```

The J2534-shaped API will be built on top of this direct transport as the
OpenPort commands for each operation are identified. Until then, protocol
discovery stays explicit and transcript-driven.

## Framing discovery probes

Use `probe` to give an explicit exchange a stable name and transcript shape:

```sh
clojure -M:run probe adapter-ping 01020304
clojure -M:run probe adapter-ping 01020304 /dev/cu.usbmodemTAlgJ3P81
```

The command validates the probe name, request bytes, and device path before
writing. It sends exactly one request, never runs sweeps, and marks the
transcript with `:vehicle-bus-traffic? false`.

Named probes are the starting point for our own J2534-shaped API. A request only
becomes a known operation after we have repeatable captured transcripts for it.

## Active adapter exchange

When the adapter is connected to the computer but not a vehicle, it is safe to
send explicit adapter-local discovery bytes. The exchange command writes only
the hex request you provide and captures any bytes returned by the adapter:

```sh
clojure -M:run exchange 01020304
clojure -M:run exchange 01020304 /dev/cu.usbmodemTAlgJ3P81
```

The response includes request, drained, and response bytes as EDN. This command
does not guess commands or run a sweep.

## Passive observe

For protocol discovery, the library can passively read bytes emitted by the
serial device. This command does not write to the adapter:

```sh
clojure -M:run observe
```

## Tests

```sh
clojure -M:test
```
