# Tactrix Reader

This project is a prototype for reading the Tactrix OpenPort 2.0 via USB and J2534.

This project is a mono-repo:

| Directory | Contents |
|---|---|
| `lib/device` | Communication with the adapter and its translation into Clojure and EDN: OBD-II requests, PID decoding, recordings, and live and replay data sources |
| `services/engine-server` | Pedestal server that streams engine data to browsers over Server-Sent Events |
| `ui/dashboard` | ClojureScript dashboard (re-frame, Reagent, React) |

## Dashboard quick start

No vehicle needed; this replays a committed recording:

```sh
cd ui/dashboard && npm install && npx shadow-cljs release app
cd ../../services/engine-server && clojure -M:run
```

Then open http://localhost:8080.
