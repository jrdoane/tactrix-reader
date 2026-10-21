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

The current macOS backend can prove host/device status. It does not yet know the
OpenPort 2.0 direct serial command for reading vehicle battery voltage, and it
does not send guessed CAN/K-line traffic.

```sh
clojure -M:run status
```

With the adapter connected to the computer but not a vehicle, expect host fields
to reflect the real device state and vehicle fields to be explicit unsupported
states:

```clojure
{:backend :mac-serial
 :host/device-present? true
 :host/openable? true
 :vehicle/power {:state :unsupported
                 :volts nil}
 :vehicle/ecu {:state :unsupported}
 :confidence :medium}
```

The voltage policy for a future backend is:

- `>= 9.0V`: vehicle power present
- `< 3.0V`: vehicle power absent
- `3.0V` to `< 9.0V`: unknown

You can call the voltage read boundary directly:

```sh
clojure -M:run vbat
```

On the current macOS direct-serial backend this returns `:unsupported`, because
the OpenPort 2.0 command for reading Vbat over `/dev/cu.usbmodem...` is not yet
known. That is different from proving vehicle power is absent.

## Direct adapter backend

This project does not depend on a vendor J2534 library. The code we own talks to
the adapter through the device path exposed by the operating system. The current
direct backend can enumerate the adapter, open it, passively observe bytes, and
run explicit request/response probes.

```sh
clojure -M:run direct-info
```

The J2534-shaped API will be built on top of this direct transport once the
OpenPort command framing is identified. Until then, protocol discovery stays
explicit and transcript-driven.

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

The active Vbat command is intentionally separate from `status`:

```sh
clojure -M:run active-vbat
```

Until the OpenPort 2.0 direct serial Vbat command is identified with
`exchange`, `active-vbat` returns `:unsupported`.

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
