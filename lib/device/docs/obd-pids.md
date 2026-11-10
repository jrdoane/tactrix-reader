# OBD-II requests and decoded PIDs

The commands here send traffic on the vehicle bus. They only send read-only
OBD-II services. Run them from `lib/device`.

## Raw requests: `obd`

`obd` sends one OBD-II request to the engine ECU over ISO15765 and prints the
reply. The request is the service byte followed by its parameters:

```sh
clojure -M:run obd 0100                              # supported PIDs 01-20
clojure -M:run obd 0906 /dev/cu.usbmodemTAlgJ3P81    # calibration verification number
```

```clojure
{:ok? true
 :protocol :iso15765
 :tx-id 2016
 :rx-id 2024
 :request {:bytes [9 6] :hex ["09" "06"]}
 :response {:bytes [73 6 1 158 179 53 81]
            :hex ["49" "06" "01" "9E" "B3" "35" "51"]
            :status 64
            :timestamp-us 761703151}
 :decoded {:ok? true
           :name :calibration-verification-numbers
           :value ["9EB33551"]}}
```

Mode 01, mode 09, and trouble code replies also get `:decoded`, from
`decode-mode-01` (see [Decoded PIDs](#decoded-pids)), `decode-mode-09` (see
[Mode 09 info types](#mode-09-info-types)), or `decode-dtc-reply` (see
[Trouble codes](#trouble-codes-trouble-codes)). Other replies are left raw.

Only read-only services are sent: 01, 02, 03, 05, 06, 07, 09, and 0A.
Service 04 (clear DTCs) and 08 (control on-board systems) return
`:service-not-read-only` without opening the device.

Each request:

1. Opens ISO15765 at 500 kbps with padding.
2. Adds a flow-control filter for `7E0`/`7E8`.
3. Sends the request and waits for the reply. The adapter reassembles
   multi-frame replies such as the VIN.
4. Closes the channel.

Functions that send several requests (`read-all-pids`, `read-vehicle-info`,
`read-trouble-codes`, `uds-scan`, `uds-supported`) do steps 1, 2, and 4 once
and repeat only step 3, using `iso15765/with-channel`. From Clojure, pass
the channel it gives to any request function to do the same.

A negative response returns `:negative-response` with its `:nrc`. NRC `0x78`
(response pending) is waited through. From Clojure, `obd-request` also takes
`:tx-id`, `:rx-id`, `:baud`, and `:timeout-ms` options for other ECUs and
buses.

## One PID: `pid`

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

The rest of the `obd` result, such as the request and raw response bytes, is
included. A PID without a decoder returns `:unknown-pid` with the raw bytes;
use `obd` for those.

## Every supported PID: `pids`

`pids` reads the supported-PID bitmaps, following each one's next-range bit,
then reads only the decoded PIDs the vehicle supports:

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

## Vehicle identity: `vehicle-info`

`vehicle-info` reads what identifies the vehicle and its engine ECU. The
engine server runs it when the dashboard connects to an adapter.

```sh
clojure -M:run vehicle-info
```

It sends these requests one at a time:

| Request | Item | Reply |
|---|---|---|
| `0902` | `:vin` | 17 ASCII characters |
| `0904` | `:calibration-ids` | 16 zero-padded ASCII bytes per ID |
| `0906` | `:calibration-verification-numbers` | 4 bytes per calibration ID, shown as hex |
| `090A` | `:ecu-name` | 20 ASCII bytes, such as `ECM-EngineControl` |
| SSM `AA` | `:ecu-id` | See [Subaru SSM reads](subaru-ssm.md) |

An item the ECU does not answer is nil, with its error under `:errors`:

```clojure
{:ok? true
 :path "/dev/cu.usbmodemTAlgJ3P81"
 :vin "..."
 :calibration-ids ["EA1T002C"]
 :calibration-verification-numbers ["9EB33551"]
 :ecu-name "ECM-EngineControl"
 :ecu-id nil
 :errors {:ecu-id {:error :timeout}}}
```

A refused request has `{:error :negative-response :nrc <code>}` instead.
`:ok?` is false only when nothing answered.

That is what this project's 2015 Impreza returns. `0902`, `0904`, `0906`, and
`090A` all answer, with the 17-character VIN, `EA1T002C`, `9EB33551`, and
`ECM-EngineControl`. SSM `AA` gets no reply. Each unanswered request waits for
the 1-second timeout, so expect a few seconds when items are missing.

The calibration verification number (CVN) is a checksum the ECU computes over
its calibration. It changes when the ECU is reflashed, so a different CVN
with the same calibration ID means the calibration was modified.

## Trouble codes: `trouble-codes`

`trouble-codes` reads the engine ECU's trouble codes. The engine server
runs it when the dashboard connects to an adapter, after `vehicle-info`.

```sh
clojure -M:run trouble-codes
```

It sends these requests one at a time:

| Request | Item | Codes |
|---|---|---|
| `03` | `:stored` | Confirmed codes, the ones that can turn on the check-engine light |
| `07` | `:pending` | Faults seen once that aren't confirmed yet |
| `0A` | `:permanent` | Codes that clearing doesn't erase; only the ECU removes them, once the monitor passes |

```clojure
{:ok? true
 :path "/dev/cu.usbmodemTAlgJ3P81"
 :stored ["P0133"]
 :pending []
 :permanent []
 :errors {}}
```

Each reply is the service byte plus `0x40`, a count of codes, and two bytes
per code; `decode-dtc-reply` turns them into SAE J2012 codes such as
`P0133`. As with `vehicle-info`, a list the ECU does not answer is nil, with
its error under `:errors`, and `:ok?` is false only when nothing answered.

It only reads codes. Clearing them is service 04, which `obd-request`
refuses to send.

PID `01`'s code count, which the live source polls, shows when the stored
codes change after connecting; reconnect to read them again.

## Mode 09 info types

`decode-mode-09` decodes these replies. On CAN, each reply has an item count
after the info type, except `00`.

| Request | `:name` | Value |
|---|---|---|
| `0900` | `:supported-info-types` | List of supported info type numbers |
| `0902` | `:vin` | String |
| `0904` | `:calibration-ids` | List of strings |
| `0906` | `:calibration-verification-numbers` | List of 8-digit hex strings |
| `0908` | `:in-use-performance` | Map, below |
| `090A` | `:ecu-name` | String |

`0908` holds the spark ignition in-use performance counters. Each monitor
has a count of times it completed and a count of drives where its conditions
were met; regulators use the ratio to check that monitors run often enough.
Older ECUs send 16 counters and leave out the secondary oxygen sensors.

```clojure
{:general-denominator 4    ; drives that met the general monitoring conditions
 :ignition-cycles 19
 :monitors {:catalyst-bank-1 {:completions 0 :conditions 0}
            :oxygen-sensor-bank-1 {:completions 4 :conditions 4}
            :egr-system {:completions 15 :conditions 4}
            :evaporative-system {:completions 1 :conditions 0}
            :secondary-oxygen-sensor-bank-1 {:completions 7 :conditions 4}
            ...}}
```

The compression ignition counters (`090B`) are not decoded.

## What the project's Impreza reports

Read-only requests on 2026-10-26, with the engine idling and no codes stored:

- **Trouble codes:** stored (`03`), pending (`07`), and permanent (`0A`) all
  return a count of 0.
- **Supported PIDs:** the bitmaps at `0100` through `0160` end at PID `65`;
  `0180` and later time out. Every supported PID has a decoder here.
- **Supported info types (`0900`):** `02`, `04`, `06`, `08`, and `0A`. `090B`
  times out.
- **Since codes were cleared:** 5 warm-ups, 164 km, 230 minutes of engine
  running. Distance and time with the MIL on are 0.
- **In-use counters (`0908`):** 19 ignition cycles and a general denominator
  of 4, as in the example above.
- **PID `65`:** the ECU has the manual transmission neutral input, so it can
  report whether the car is in gear.

Inference, not confirmed: in-use counters are normally reset only when the
ECU's memory is reset, by reflashing or losing power, not by clearing codes.
Counts this low suggest that happened recently, about the same time the
codes were cleared. The evaporative monitor was still incomplete then, which
fits; it needs particular conditions, such as a cold start, to run.

## Decoded PIDs

Not every vehicle supports every PID; `pid 00` shows which PIDs 01-20 it does.

| PID | Name | Value |
|---|---|---|
| 00, 20, 40, 60 | `supported-pids-01-20`, `-21-40`, `-41-60`, `-61-80` | List of PID numbers, also as hex strings in `:value-hex` |
| 01 | `monitor-status` | Map: `:mil-on?`, `:dtc-count`, `:ignition`, `:monitors` |
| 03 | `fuel-system-status` | Map: `:system-1`, `:system-2` (e.g. `:closed-loop`) |
| 04 | `engine-load` | Percent |
| 05 | `coolant-temp` | Celsius |
| 06, 07 | `short-term-fuel-trim-bank-1`, `long-term-fuel-trim-bank-1` | Percent |
| 0B | `intake-manifold-pressure` | kPa |
| 0C | `engine-rpm` | rpm |
| 0D | `vehicle-speed` | km/h |
| 0E | `timing-advance` | Degrees before TDC |
| 0F | `intake-air-temp` | Celsius |
| 10 | `maf-air-flow` | g/s |
| 11 | `throttle-position` | Percent |
| 13 | `oxygen-sensors-present` | List such as `:bank-1-sensor-1` |
| 15 | `oxygen-sensor-bank-1-sensor-2` | Map: `:volts`, `:fuel-trim-percent` (nil if unused) |
| 1C | `obd-standard` | Keyword such as `:obd-and-obd-ii` |
| 1F | `run-time` | Seconds since engine start |
| 21 | `distance-with-mil-on` | km |
| 24 | `wideband-oxygen-sensor-1-voltage` | Map: `:lambda`, `:volts` |
| 2C | `commanded-egr` | Percent |
| 2D | `egr-error` | Percent |
| 2E | `commanded-evap-purge` | Percent |
| 2F | `fuel-level` | Percent |
| 30 | `warm-ups-since-codes-cleared` | Count |
| 31 | `distance-since-codes-cleared` | km |
| 33 | `barometric-pressure` | kPa |
| 34 | `wideband-oxygen-sensor-1-current` | Map: `:lambda`, `:milliamps` |
| 3C-3F | `catalyst-temp-bank-1-sensor-1`, `-bank-2-sensor-1`, `-bank-1-sensor-2`, `-bank-2-sensor-2` | Celsius |
| 41 | `monitor-status-this-drive-cycle` | Map: `:ignition`, `:monitors` |
| 42 | `control-module-voltage` | Volts |
| 43 | `absolute-load` | Percent |
| 44 | `commanded-lambda` | Lambda (1.0 is stoichiometric) |
| 45 | `relative-throttle-position` | Percent |
| 46 | `ambient-air-temp` | Celsius |
| 47, 48 | `absolute-throttle-position-b`, `-c` | Percent |
| 49, 4A, 4B | `accelerator-pedal-position-d`, `-e`, `-f` | Percent |
| 4C | `commanded-throttle-actuator` | Percent |
| 4D | `time-with-mil-on` | Minutes |
| 4E | `time-since-codes-cleared` | Minutes |
| 51 | `fuel-type` | Keyword such as `:gasoline` |
| 53 | `evap-vapor-pressure-absolute` | kPa |
| 5A | `relative-accelerator-pedal-position` | Percent |
| 5C | `oil-temp` | Celsius |
| 65 | `auxiliary-io-status` | Map of reported inputs, e.g. `:manual-transmission-in-gear?` (false in neutral) |

Notes:

- MIL is the check-engine light.
- `:monitors` lists only the readiness monitors the vehicle has, each as
  `:complete` or `:incomplete`.
- Fuel system, fuel type, and OBD standard codes the decoder does not
  recognise are returned as numbers.
