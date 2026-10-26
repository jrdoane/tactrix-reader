# Tactrix Reader

A prototype for reading engine data through a Tactrix OpenPort 2.0 over USB.
It talks to the adapter directly through its macOS serial device, with no
vendor J2534 library, and streams decoded OBD-II values to a browser
dashboard.

## Layout

| Directory | Contents |
|---|---|
| [`lib/device`](lib/device/README.md) | Adapter communication: device discovery, OBD-II requests, PID decoding, recordings, and live and replay data sources |
| [`services/engine-server`](services/engine-server/README.md) | Pedestal server that streams engine data to browsers over Server-Sent Events |
| [`ui/dashboard`](ui/dashboard/README.md) | ClojureScript dashboard (re-frame, Reagent, React) |

## Quick start

No vehicle needed; this replays a committed recording:

```sh
cd ui/dashboard && npm install && npx shadow-cljs release app
cd ../../services/engine-server && clojure -M:run
```

Then open http://localhost:8080.

To read a real vehicle, plug the OpenPort into the OBD-II port, turn the
ignition on, and run `clojure -M:run live` instead.

## Documentation

- [Device library](lib/device/README.md): command-line tool and Clojure API
- [OBD-II requests and decoded PIDs](lib/device/docs/obd-pids.md)
- [Recordings](lib/device/docs/recordings.md): capturing and exporting CAN traffic
- [OpenPort serial protocol](lib/device/docs/openport-protocol.md): reverse-engineered adapter commands
- [Engine server](services/engine-server/README.md): running, endpoints, and the stream format
- [Dashboard](ui/dashboard/README.md): building and namespace layout

## Tests

```sh
(cd lib/device && clojure -M:test)
(cd services/engine-server && clojure -M:test)
```
