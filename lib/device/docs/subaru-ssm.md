# Subaru SSM reads

Some engine values, such as knock correction, are not standard OBD-II PIDs.
Subaru ECUs expose them through Subaru Select Monitor (SSM) memory reads.
`ssm-read` sends those reads over the same ISO15765 channel as
[`obd`](obd-pids.md). Run these commands from `lib/device`.

**Not yet confirmed on a vehicle.** The request format below comes from
public forum notes (RomRaider and AutoSport Labs), not from probing this
project's 2015 Impreza. When it is tried, record what happens in
[OpenPort serial protocol](openport-protocol.md#on-a-vehicle) and update this
page.

## Reading addresses: `ssm-read`

`ssm-read` sends traffic on the vehicle bus. Give it one or more 3-byte hex
ECU addresses, separated by commas:

```sh
clojure -M:run ssm-read 000022
clojure -M:run ssm-read FF6A04,FF6A05,FF6A06,FF6A07 /dev/cu.usbmodemTAlgJ3P81
```

From Clojure, pass the addresses as integers:

```clojure
(device/ssm-read path [0x000022])
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

1. Find the ECU's calibration ID. `ssm-read` cannot read it yet.
2. Look up that ID in RomRaider's logger definitions (`logger_*.xml`). FBKC
   and FLKC are extended parameters there, such as "Fine Learning Knock
   Correction (4-byte)*", with an address for each ECU ID.
3. Read the four addresses starting at that address. These values are
   usually big-endian 32-bit floats in degrees:

```clojure
(let [{:keys [values]} (device/ssm-read path (map #(+ address %) (range 4)))]
  (Float/intBitsToFloat
   (unchecked-int (reduce (fn [acc b] (+ (* acc 256) b)) 0 (map :value values)))))
```

Check the logger definition's conversion for each parameter rather than
assuming a float.

## Safety

`ssm-read` builds only the `A8` read command (`ssm-read-addresses-command`).
SSM also has commands that write ECU memory (`B0` and `B8`). Never send them.
Forum notes say restricted addresses read back as `FF` rather than failing.
