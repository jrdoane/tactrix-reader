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
`\r\n`; replies are ASCII lines that start with `ar`.

| Request | Reply | Meaning |
|---|---|---|
| `ati` | `ari main code version : 1.17.4877` | Firmware version |
| `ata` | `aro` | OK |
| `atz` | `aro` | OK (likely reset) |
| `atr <pin>` | `arr <pin> <value>` | Pin voltage, assumed millivolts |
| any | `are <code>` | Error; codes match J2534 status codes |

Pin reads seen so far: `atr 16` (OBD-II battery +12V) returns about 150-175
with no vehicle, `atr 12` returns 0, and `atr 17` returns about 5533, likely an
internal supply. Other pins return `are 19` (`ERR_PIN_INVALID`).

Other error codes seen: 3 (`ERR_INVALID_PROTOCOL_ID`), 7 (`ERR_FAILED`), and 10
(`ERR_INVALID_MSG`). Sending `atc`, `atg`, `ato`, `ats`, `atw`, and `atx` with
no arguments returns `are 7`; `atf`, `atk`, `atl`, `atm`, and `atn` return
`are 3`; `atp`, `att`, and `aty` return `are 10`. These are probably J2534
operations that need arguments. `atb`, `atd`, `ate`, `ath`, `atj`, `atq`, and
`atu` get no reply.

Not yet confirmed:

- The millivolt unit. Check `vbat` against a vehicle or a 12V bench supply on
  pins 16 and 4/5; expect roughly 12000-14000.
- `atv` with arguments. It may set programming voltage on a pin, so do not
  send it until its arguments are understood.

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
