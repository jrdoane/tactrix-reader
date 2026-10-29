# Engine dashboard

ClojureScript front end built with re-frame, Reagent 2, and React 19. It
connects to the [engine server's](../../services/engine-server/README.md)
`/stream` endpoint and shows live engine data.

The page has:

- An adapter picker with Rescan, Connect, Disconnect, and Replay recording.
- Panels for the source, the adapter (serial, device, firmware, battery
  voltage), the vehicle (VIN, calibration ID and CVN, ECU name, SSM ECU ID, or why
  each could not be read), and diagnostics from PID 01 (check-engine light,
  stored codes, readiness monitors).
- Large readouts for RPM, speed, gear, coolant, and throttle, then grouped
  tiles for the other polled PIDs. PIDs also decoded from the car's broadcast
  CAN traffic are tagged CAN.
- A "From the CAN bus" section with signals identified from that traffic but
  matched to no PID, each tagged inferred: steering angle, brake, brake
  pressure, parking brake, driver's seatbelt, wheel speeds, accelerations,
  yaw rate, fuel flow, and outside temperature. See
  [broadcast CAN traffic](../../lib/device/docs/can-broadcast.md#inferred-signals).
- A table of every PID received, with where each came from.

## Building

The build writes to `services/engine-server/resources/public/assets/js`, where
the server serves it. From this directory:

```sh
npm install
npx shadow-cljs release app    # one-off optimized build
npx shadow-cljs watch app      # development build with hot reload
```

When using `watch`, start the server with `PEDESTAL_DEV_MODE=true` so its
Content-Security-Policy allows hot reload.

## Layout

Namespaces are under `tactrix-reader.dashboard`:

| Namespace | Role |
|---|---|
| `core` | Mounts the app with `reagent.dom.client` |
| `stream` | `:stream/connect` effect: opens the `EventSource` and decodes Transit |
| `http` | `:http` effect: calls `/api` with Transit and dispatches the reply |
| `events` | App state: connection, source status and info, latest sample per PID and value per inferred signal, adapters |
| `subs` | Subscriptions |
| `views` | Page: source controls, info panels, readouts, and a table of every value |
