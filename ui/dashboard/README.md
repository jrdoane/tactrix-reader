# Engine dashboard

ClojureScript front end built with re-frame, Reagent 2, and React 19. It
connects to the [engine server's](../../services/engine-server/README.md)
`/stream` endpoint and shows live engine data.

The page has:

- An adapter picker with Rescan, Connect, Disconnect, and Poll DIDs, which
  alternates PID requests with the scanned UDS DIDs.
- Record and Stop recording, with a note, while connected live.
- Panels for the source, the adapter (serial, device, firmware, battery
  voltage), the vehicle (VIN, calibration ID and CVN, ECU name, SSM ECU ID, or why
  each could not be read), and diagnostics from PID 01 (check-engine light,
  stored codes, readiness monitors).
- Large readouts for RPM, speed, gear, coolant, and throttle, then grouped
  tiles for the other polled PIDs. PIDs also decoded from the car's broadcast
  CAN traffic are tagged CAN. Pressures, which the PIDs report in kPa, are
  shown in bar (absolute).
- A "From the CAN bus" section, tagged inferred, with signals identified from
  that traffic but matched to no PID: steering angle, brake, brake pressure,
  parking brake, driver's seatbelt, accelerations, yaw rate, the four wheel
  speeds, whether each door is open, fuel flow, and outside temperature. Gear, shown with the main
  readouts, is tagged inferred on its own. See
  [broadcast CAN traffic](../../lib/device/docs/can-broadcast.md#inferred-signals).
- A table of every PID received, with where each came from.
- The saved recordings, grouped by the vehicle each was recorded on, with
  Map and Replay buttons for each. See
  [recordings](../../lib/device/docs/recordings.md#recording-from-the-dashboard).

The **Map** view, toggled in the header, bins one value by two others, like
an ECU table: by default timing advance by RPM and load (g/rev, MAF × 60 /
RPM). It works on any recording, or on the current stream (live or a replay)
as it arrives.

- Pick the value across, the value down (each with editable breakpoints), the
  value shown, and its mean, lowest, highest, or row count. Cells with fewer
  rows than Min rows are dimmed. Trims and accelerations are colored around 0
  and lambda around 1; everything else from its lowest to highest value.
- Filters keep only rows where a value is at least or at most a number, such
  as coolant ≥ 75 °C.
- The timeline plots any value over the whole log. Drag across it to map only
  that part. Clicking a cell shows its details and marks its rows on the
  timeline.

See [mapping a recording](../../lib/device/docs/recordings.md#mapping-a-recording).

## Building

The build writes to `services/engine-server/resources/public/assets/js`, where
the server serves it. From this directory:

```sh
npm install
npx shadow-cljs release app    # one-off optimized build
npx shadow-cljs watch app      # development build with hot reload
npm test                       # node tests of the pure namespaces
npm run lint                   # clj-kondo
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
| `format` | Labels, units, and number formats shared by `views` and `map-view` |
| `series` | Pure: time-series logs from recordings or the stream, holding values on a grid, axes, and binning. Tested by `npm test` |
| `map-view` | The Map view: controls, filters, timeline, heat table, and cell details |
