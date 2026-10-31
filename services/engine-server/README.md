# Engine server

Pedestal server for the engine dashboard. It runs one data source at a time,
batches its samples, and streams them to browsers over Server-Sent Events. The
dashboard can switch the source to an adapter, any saved recording, or off,
and record what a live source receives.

## Running

Build the [dashboard](../../ui/dashboard/README.md) first, then from this
directory:

```sh
clojure -M:run                              # replay the newest recording
clojure -M:run replay 2026-10-24-idle       # a name in ~/.tactrix-reader/recordings, or a path
clojure -M:run live                         # poll the vehicle on the first detected adapter
clojure -M:run live /dev/cu.usbmodemTAlgJ3P81
```

Open http://localhost:8080. Set `PORT` to use another port.

The arguments choose the first source. `replay` plays a
[recording's](../../lib/device/docs/recordings.md) samples back in real
time, in a loop, and needs no hardware. `live` sends read-only requests on the
vehicle bus. Both use the device library's
[live and replay sources](../../lib/device/README.md#live-and-replay-sources).
With no arguments and no recordings yet, the server starts with no source.

Recordings are listed from and saved to `~/.tactrix-reader/recordings` (see
[where recordings live](../../lib/device/docs/recordings.md#where-recordings-live)).

## Connecting to an adapter

`sources.clj` switches sources one at a time. Connecting to an adapter:

1. Stops the current source and resets the dashboard.
2. Reads battery voltage on OBD-II pin 16 (no vehicle bus traffic). It stops
   with `:no-vehicle-power` if there is none.
3. Reads the vehicle's identity with
   [`read-vehicle-info`](../../lib/device/docs/obd-pids.md#vehicle-identity-vehicle-info):
   VIN, calibration IDs and verification numbers, ECU name, and SSM ECU ID.
   Status is `:identifying` meanwhile.
4. Starts polling mode 01 PIDs, alternated with the scanned UDS DIDs when the
   request has `:dids? true`.

The status goes `:connecting`, `:identifying`, then `:running`, or `:failed`
with an `:error`.

## Endpoints

| Path | Response |
|---|---|
| `/` | Dashboard page |
| `/assets/...` | Compiled ClojureScript and CSS from `resources/public/assets` |
| `/stream` | Server-Sent Events with Transit JSON data |
| `/api/snapshot` | Latest status, info, recording, samples, signals, and DIDs, as Transit JSON |
| `GET /api/adapters` | `{:adapters [{:vendor :product :serial :path :paths}]}` from `scan-devices` |
| `GET /api/recordings` | `{:dir "..." :recordings [...]}`: each recording's name, time, length, vehicle (with its config.edn description), note, and size, newest first |
| `POST /api/source` | Switch source. Body: `{:type :live :path "..." :dids? true}`, `{:type :replay :recording "<name>"}`, or `{:type :none}`. Replies 202 with `{:source ...}`; progress arrives on `/stream` |
| `POST /api/recording` | `{:action :start :note "..."}` or `{:action :stop}`: record the running live source. Replies 202, or 409 with `:not-live`, `:not-running`, `:already-recording`, or `:not-recording` |

The `/api` routes answer only requests addressed to `localhost`, `127.0.0.1`,
or `::1` (403 otherwise), which defeats DNS rebinding. `POST` requests
require a `application/transit+json` body (415 otherwise), which other sites
cannot send without a CORS preflight the server never approves.
`POST /api/source` accepts only paths that `scan-devices` currently reports
(400 `:unknown-adapter` otherwise) and names that `/api/recordings` lists
(400 `:unknown-recording` otherwise), so a request cannot make the server
write to any other file or read any other directory.

`/stream` sends a `snapshot` event on connect, then:

- `samples`: `{:samples [...] :signals [...]}` every 100 ms, with the latest
  sample of each PID and the latest value of each inferred CAN signal that
  arrived since the last batch. A sample is
  `{:pid :name :value :unit :timestamp-us}`, plus `:can-id` when it was
  decoded from broadcast traffic. A signal is
  `{:signal :value :unit :can-id :timestamp-us}`.
- `status`: the source's state, such as `{:source :replay :state :running}`,
  sent when it changes. A live source's `:running` status includes the
  adapter `:firmware`.
- `info`: what the source knows, sent once per switch:
  `{:source :adapter {...} :vehicle {...} :recording {...}}`. A live source
  has the adapter's product, serial, path, and `:battery-volts`, and the
  vehicle's `read-vehicle-info` result. A replay has the recording's vehicle,
  name, time, length, and note from `meta.edn`.
- `recording`: `{:state :recording :name :dir :started-at}` when a recording
  starts, and `{:state :saved :name :dir :duration-ms :stopped-by}` when it
  is saved. Switching sources saves it first.
- `snapshot`: sent again, empty, when the source switches.

A browser that falls behind drops old batches instead of slowing the source.

## Development

Set `PEDESTAL_DEV_MODE=true` when running the dashboard with
`npx shadow-cljs watch app`. The Content-Security-Policy then allows
shadow-cljs hot reload (eval and its websocket on port 9630); otherwise only
same-origin resources are allowed.

```sh
PEDESTAL_DEV_MODE=true clojure -M:run
```

## Tests

```sh
clojure -M:test
clojure -M:lint    # clj-kondo
```
