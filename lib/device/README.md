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
