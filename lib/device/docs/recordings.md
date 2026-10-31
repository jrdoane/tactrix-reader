# Recordings

A recording captures raw CAN traffic from the vehicle while polling a set of
mode 01 PIDs, so decoding can be worked on and the dashboard run without a
vehicle. Recordings are local data, not part of the repository.

## Where recordings live

Recordings are saved in `~/.tactrix-reader/recordings`, one folder each.
`$TACTRIX_READER_HOME` replaces `~/.tactrix-reader`, and `:recordings-dir` in
`~/.tactrix-reader/config.edn` moves the recordings folder. config.edn can
also name vehicles, keyed by VIN or calibration ID, for the dashboard's
recordings list:

```clojure
{:vehicles {"EA1T002C" {:description "2015 Subaru Impreza, manual transmission"}}}
```

Wherever a command takes a recording, a bare name such as `my-drive` means
that folder in the recordings directory, and anything containing `/` is a
path.

## Recording from the dashboard

While the dashboard is connected live, **Record** saves what the live source
receives as a new recording, named from the local date and time, until
**Stop recording** (or until the source stops or switches). Polling is not
interrupted, so the recording starts at the next received message rather
than with the channel setup that a CLI recording has. The optional **Note**
is saved as `:note` in `meta.edn`. With **Poll DIDs** checked, the live source
alternates PIDs with the `scanned` DIDs, as `record-dids` does below;
changing it reconnects.

The **Recordings** list shows every recording, grouped by vehicle, and
replays any of them.

## Recording from the command line

Run these commands from `lib/device`. `record` sends traffic on the vehicle
bus. It first reads the vehicle's identity (`vehicle-info`), then records
until Ctrl-C, or for the given number of seconds. With no name, or `-`, the
recording is named from the date and time:

```sh
clojure -M:run record
clojure -M:run record my-drive
clojure -M:run record my-drive 120 /dev/cu.usbmodemTAlgJ3P81
clojure -M:run recordings          # list them, newest first
```

It writes `raw.bin` to disk as bytes arrive. When it stops, whether at the
time limit, on Ctrl-C, or because the adapter failed (for example, it was
unplugged), it closes the channel and writes `meta.edn` and the CSVs from what
it captured. `:stopped-by` in the result and in `meta.edn` says which:
`:duration`, `:stop`, or `:error`. After an error the result has `:ok? false`,
and `meta.edn` has the error message. If the process is killed outright,
`raw.bin` is still on disk up to that point; run `export-recording` on it to
get the CSVs. A recording that never opens the CAN channel writes no files.

It opens CAN at 500 kbps with a pass-all filter and polls with the same
schedule as the [live source](../README.md#live-and-replay-sources): one
read-only request to `7E0` every 15 ms, covering RPM, speed, load,
temperatures, trims, commanded and measured lambda, fuel system status, the
rear oxygen sensor, throttle and pedal, the neutral input, voltage,
barometric pressure, and fuel level. Fast PIDs are sampled about every 210 ms
and slow ones about every 2.9 seconds.

From Clojure, `record` takes `:duration-ms`, `:stop`, `:pids`,
`:interval-ms`, `:tx-id`, `:rx-id`, `:baud`, and `:vehicle` and `:note` for
`meta.edn`. It records for 60 seconds by
default; `:duration-ms nil` removes the limit, and delivering the `:stop`
promise ends the recording early. Pass `:pids []` to only listen.

## Recording UDS data identifiers

`record-dids` records like `record`, but alternates each PID request with a
UDS service 22 request for a data identifier (DID), so the DIDs can be
matched against the PIDs on the same clock. See
[Trying UDS service 22](subaru-ssm.md#trying-uds-service-22). It sends
traffic on the vehicle bus. Give it comma-separated hex DIDs, or `scanned`
for the 52 single-frame DIDs `uds-scan` found in `1000`-`10FF`
(`uds/scanned-dids`):

```sh
clojure -M:run record-dids - scanned
clojure -M:run record-dids my-drive 10A5,10BA,10BE 120 /dev/cu.usbmodemTAlgJ3P81
```

Each DID request is the single frame `03 22 <hi> <lo> 00 00 00 00` to
`7E0`. Requests still go out every 15 ms, but half of them are now DIDs, so
each fast PID is sampled about every 420 ms and each slow PID about every
5.9 seconds. With `scanned`, each DID is read about every 1.6 seconds; with
three DIDs, about every 90 ms.

Only single-frame replies (up to 4 data bytes) are decoded. A DID whose reply
needs more than one frame, such as `101E`, gets only a first frame: the raw
CAN channel sends no flow control, so the ECU never sends the rest. Leave such
DIDs out; the ECU may hold off other replies while it waits.

In a 10-second trial at idle, all 52 `scanned` DIDs replied and every
request got a reply; see
[the trial results](subaru-ssm.md#scanning-for-dids-uds-scan).

From Clojure, pass `:dids` to `record` as integers. `meta.edn` lists them
under `:polling :dids`. The replay source emits each single-frame reply as a
`:did` event, decoded as far as `uds/did-signals` knows, and the dashboard
shows them under "UDS data identifiers".

## Files

| File | Contents |
|---|---|
| `raw.bin` | Exact bytes the adapter sent, including command replies from a CLI recording |
| `meta.edn` | When it started, the vehicle's identity, the note, duration and why it stopped, firmware, bus settings, polling schedule, and counts |
| `frames.csv` | Every CAN frame, including copies of the polling requests |
| `pids.csv` | Decoded PID replies |
| `dids.csv` | Single-frame service 22 replies, undecoded; only when there are any |

`meta.edn` is written when a recording starts, with `:in-progress? true`,
and rewritten when it stops. A recording still marked in progress was cut
off by a crash; its `raw.bin` is intact up to that point. `:vehicle` holds
whichever of `:vin`, `:calibration-ids`,
`:calibration-verification-numbers`, `:ecu-name`, and `:ecu-id` were read.

`raw.bin` is the source of truth. It uses the adapter's
[received message format](openport-protocol.md#received-messages) and can be
parsed with `parse-adapter-stream`.

Both CSVs are derived from `raw.bin`. Regenerate them with:

```sh
clojure -M:run export-recording 2026-10-24-idle
```

| CSV | Columns |
|---|---|
| `frames.csv` | `t_s,timestamp_us,channel,status,id,dlc,data` |
| `pids.csv` | `t_s,timestamp_us,pid,name,value,unit` |
| `dids.csv` | `t_s,timestamp_us,did,data` |

`t_s` is seconds since the first frame and `timestamp_us` is the adapter's
microsecond clock, so PID values line up with bus traffic. `id`, `status`,
`pid`, `did`, and `data` are hex.

## Reference recordings

The analysis in these docs used the recordings below. They were committed
under `lib/device/recordings` until the move to `~/.tactrix-reader`, so they
are in git history before that commit, and they now live in the author's
recordings folder. All four are from the same car (calibration `EA1T002C`),
which was added to their `meta.edn` when they moved.

### `2026-10-24-idle`

One minute of a warm 2015 Subaru Impreza (manual) idling in neutral: about
57,600 frames and 2,400 PID samples.

It predates the current polling schedule. It polled 20 PIDs in turn, one every
25 ms, so each value is sampled only every 500 ms.

### `2026-10-28-drive`

Nine minutes of driving the same car: about 561,000 frames and 37,000 PID
samples. It starts with the ignition on and the engine off, with a cold
engine (coolant 47 °C); the engine starts at 5 s. It covers town and highway
driving through all five gears up to 139 km/h, a full-throttle pull to
6,400 rpm, several stops, and a warm idle in neutral at the end. It was
stopped with Ctrl-C.

It predates PIDs `45`, `49`, `4C`, and `65` in the polling schedule, so each
fast PID was sampled about every 135 ms and each slow one every 1.35 s.

### `2026-10-29-did-drive`

4.9 minutes of town driving in the same car with
`record-dids ... scanned`: about 19,400 polling requests, all answered, with
9,713 PID samples and 9,712 DID samples in `dids.csv`. The engine was warm
(coolant 92-99 °C). It covers 0-82 km/h, up to 5,040 rpm, several full-load
pulls, coasting in fuel cut, and an idle at each end. It was stopped with
Ctrl-C. Because half the requests are DIDs, each fast PID was sampled about
every 420 ms. What the DIDs showed is in
[What the DIDs hold](subaru-ssm.md#what-the-dids-hold).

### `2026-10-30-did-drive`

14.4 minutes of town and highway driving in the same car with
`record-dids ... scanned`: 57,568 polling requests, with 28,772 PID samples
and 28,771 DID samples (25 requests got no reply). Coolant was 84-96 °C and
oil warmed from 74 to 98 °C. It covers 0-123 km/h, up to 5,463 rpm, full-load
pulls, coasting in fuel cut, and an idle at each end. What it added is in
[A second drive](subaru-ssm.md#a-second-drive-2026-10-30).
