# OBD-II requests and decoded PIDs

The commands here send traffic on the vehicle bus. They only send read-only
OBD-II services. Run them from `lib/device`.

## Raw requests: `obd`

`obd` sends one OBD-II request to the engine ECU over ISO15765 and prints the
reply. The request is the service byte followed by its parameters:

```sh
clojure -M:run obd 0100                              # supported PIDs 01-20
clojure -M:run obd 0902 /dev/cu.usbmodemTAlgJ3P81    # VIN
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

Only read-only services are sent: 01, 02, 03, 05, 06, 07, 09, and 0A.
Service 04 (clear DTCs) and 08 (control on-board systems) return
`:service-not-read-only` without opening the device.

Each request:

1. Opens ISO15765 at 500 kbps with padding.
2. Adds a flow-control filter for `7E0`/`7E8`.
3. Sends the request and waits for the reply. The adapter reassembles
   multi-frame replies such as the VIN.
4. Closes the channel.

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
