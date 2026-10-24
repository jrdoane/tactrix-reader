# Engine server

Pedestal server for the engine dashboard. It runs one data source, batches its
samples, and streams them to browsers over Server-Sent Events.

## Running

Build the dashboard first (see `ui/dashboard`), then from this directory:

```sh
clojure -M:run                              # replay the committed idle recording
clojure -M:run replay ../../lib/device/recordings/2026-10-24-idle
clojure -M:run live                         # poll the vehicle on the first detected adapter
clojure -M:run live /dev/cu.usbmodemTAlgJ3P81
```

Open http://localhost:8080. Set `PORT` to use another port.

`live` sends read-only mode 01 requests on the vehicle bus. `replay` plays a
recording's PID samples back in real time, in a loop, and needs no hardware.

## Endpoints

| Path | Response |
|---|---|
| `/` | Dashboard page |
| `/assets/...` | Compiled ClojureScript and CSS from `resources/public/assets` |
| `/stream` | Server-Sent Events, Transit JSON data |
| `/api/snapshot` | Latest status and samples, Transit JSON |

`/stream` sends a `snapshot` event on connect, then:

- `samples`: `{:samples [...]}` every 100 ms, with the latest sample of each
  PID that changed. A sample is `{:pid :name :value :unit :timestamp-us}`.
- `status`: the source's state, such as `{:source :replay :state :running}`,
  sent when it changes.

A browser that falls behind drops old batches instead of slowing the source.

## Development

Set `PEDESTAL_DEV_MODE=true` when using `npx shadow-cljs watch app`. The
Content-Security-Policy then allows shadow-cljs hot reload (eval and its
websocket on port 9630); otherwise only same-origin resources are allowed.

```sh
clojure -M:test
```
