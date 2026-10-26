# Device library

Talks to a Tactrix OpenPort 2.0, or a similar USB/J2534 adapter that appears on
macOS as a `/dev/cu.usbmodem*` device. It does not use a vendor J2534 library:
it writes the adapter's own serial commands (see
[OpenPort serial protocol](docs/openport-protocol.md)) to the device path.

The library provides:

- Device discovery and adapter checks that need no vehicle
- Read-only OBD-II requests and decoding of mode 01 PIDs
- Subaru SSM memory reads, for values such as knock correction
- Recording of raw CAN traffic with polled PIDs
- Live and replay data sources for the [engine server](../../services/engine-server/README.md)

More detail:

- [OBD-II requests and decoded PIDs](docs/obd-pids.md)
- [Subaru SSM reads](docs/subaru-ssm.md)
- [Recordings](docs/recordings.md)
- [OpenPort serial protocol](docs/openport-protocol.md)

## Command line

Run commands from this directory. Each prints EDN. Commands that take a device
path use the first detected adapter when it is left out.

| Command | Does | Vehicle bus traffic |
|---|---|---|
| `scan` | List detected OpenPort and usbmodem devices | No |
| `open [path]` | Open and close the device | No |
| `status` | Report host, device, and vehicle power status | No |
| `vbat` | Read battery voltage on OBD-II pin 16 | No |
| `direct-info` | Describe what the direct backend implements and which operations send vehicle bus traffic | No |
| `observe [path]` | Passively read bytes from the device | No |
| `exchange <hex> [path]` | Write the given bytes and capture the reply | No, if no vehicle is connected |
| `probe <name> <hex> [path]` | Like `exchange`, recorded as a named transcript | No, if no vehicle is connected |
| `obd <hex> [path]` | Send one read-only OBD-II request | Yes |
| `pid <name-or-hex> [path]` | Read and decode one mode 01 PID | Yes |
| `pids [path]` | Read every decodable PID the vehicle supports | Yes |
| `ssm-read <addresses> [path]` | Read one byte at each comma-separated hex ECU address with Subaru SSM | Yes |
| `record <dir> [seconds] [path]` | Record CAN traffic while polling PIDs | Yes |
| `export-recording <dir>` | Regenerate a recording's CSVs from `raw.bin` | No |

`obd`, `pid`, and `pids` are covered in
[OBD-II requests and decoded PIDs](docs/obd-pids.md); `ssm-read` in
[Subaru SSM reads](docs/subaru-ssm.md); `record` and `export-recording` in
[Recordings](docs/recordings.md).

Failures are returned as EDN with `:ok? false` and an `:error` keyword rather
than thrown, for example when the device is unplugged, busy, or blocked by
permissions.

### Checking the adapter

These work with the adapter plugged into the computer and nothing else.

```sh
clojure -M:run scan
```

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

```sh
clojure -M:run open
```

```clojure
{:ok? true
 :path "/dev/cu.usbmodemTAlgJ3P81"
 :opened? true}
```

### Vehicle power

`status` and `vbat` read the voltage on OBD-II pin 16 (battery +12V) with the
adapter's `atr 16` command. They send nothing on the vehicle bus.

```sh
clojure -M:run status
```

With no vehicle connected, pin 16 floats near 0V:

```clojure
{:backend :mac-serial
 :host/device-present? true
 :host/openable? true
 :vehicle/power {:state :absent
                 :volts 0.152}
 :vehicle/ecu {:state :unsupported}
 :confidence :medium}
```

| Voltage | `:state` |
|---|---|
| 9.0V or more | `:present` |
| 3.0V to under 9.0V | `:unknown` |
| under 3.0V | `:absent` |

`vbat` returns the raw reading:

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

If the adapter replies with an error or something unexpected, the result has
`:ok? false`, `:state :unknown`, and an `:error` keyword. That is not the same
as vehicle power being absent.

### Protocol discovery

These commands were used to work out the
[OpenPort serial protocol](docs/openport-protocol.md). Each one writes only
the bytes it is given and never runs a sweep.

```sh
clojure -M:run observe                      # read only; writes nothing
clojure -M:run exchange 01020304            # write bytes, capture the reply
clojure -M:run probe adapter-ping 01020304  # same, as a named transcript
```

`exchange` returns the request, any bytes drained beforehand, and the response.
`probe` validates the probe name, request bytes, and device path before
writing, sends exactly one request, and marks its transcript
`:vehicle-bus-traffic? false`. Only send these with no vehicle connected:
arbitrary bytes can be adapter commands that transmit on the bus.

## Clojure API

The main entry points in `tactrix-reader.device`:

| Function | Purpose |
|---|---|
| `scan-devices` | Detected adapters, as printed by `scan` |
| `vehicle-status`, `read-vbat` | Adapter and vehicle power checks |
| `obd-request` | One read-only OBD-II request over ISO15765 |
| `read-pid`, `read-all-pids`, `decode-mode-01` | Mode 01 PID reads and decoding |
| `ssm-read` | [Subaru SSM](docs/subaru-ssm.md) memory reads over ISO15765 |
| `record`, `export-recording`, `recording-samples` | [Recordings](docs/recordings.md) |
| `parse-adapter-stream` | Splits raw adapter bytes into reply lines and received messages |
| `start-live-source!`, `start-replay-source!` | Data sources, below |

Functions that take a device accept either a path string or a device map from
`scan-devices`.

### Live and replay sources

`start-live-source!` and `start-replay-source!` run on a background thread and
call an `:emit` function with the same events, so a consumer such as the engine
server cannot tell them apart:

- `{:type :sample :pid :name :value :unit :timestamp-us}` for each PID reading
- `{:type :status :source :live|:replay :state :running|:stopped|:failed ...}`

Each returns a map whose `:stop!` function stops the source and waits for it
to finish.

```clojure
(def source
  (device/start-replay-source! "recordings/2026-10-24-idle" {:emit prn}))
((:stop! source))
```

The live source keeps one adapter session open on the raw CAN channel and
polls with the default schedule:

- One read-only mode 01 request to `7E0` every 15 ms
  (`default-poll-interval-ms`).
- The schedule (`default-poll-schedule`) requests every fast-changing PID
  (RPM, speed, load, throttle, pedal, MAF, manifold pressure, timing), then one
  slow PID (temperatures, voltage, fuel level, trims, lambda, check-engine
  status), and repeats.
- Each fast PID is sampled about every 135 ms and each slow PID about every
  1.35 seconds.

`record` uses the same session code and schedule. Pass `:schedule`,
`:interval-ms`, `:tx-id`, `:rx-id`, or `:baud` to change them.

The replay source plays a recording's PID samples paced by their timestamps.
It loops by default (`:loop?`), shifting timestamps so they keep increasing,
and `:speed` scales playback.

## Tests

```sh
clojure -M:test
```
