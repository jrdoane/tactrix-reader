# Engine dashboard

ClojureScript front end built with re-frame, Reagent 2, and React 19. It
connects to the [engine server's](../../services/engine-server/README.md)
`/stream` endpoint and shows live engine data.

The page has:

It has five tabs, kept in the URL (`#signals`, `#bus`, `#map`,
`#recordings`), under one source bar.

The **source bar** is on every tab:

- What is playing, on which car, and whether the check-engine light is on.
- For a replay, a transport: pause, the position on a slider that seeks, and
  the speed.
- An adapter picker with Rescan, Connect, and Poll DIDs, which alternates
  PID requests with the scanned UDS DIDs, and Disconnect or Stop.
- Record and Stop recording, with a note, while connected live.
- Details, which opens panels for the source, the adapter (serial, device,
  firmware, battery voltage), the vehicle (VIN, calibration ID and CVN, ECU
  name, SSM ECU ID, or why each could not be read), and diagnostics from
  PID 01 (check-engine light, stored codes, readiness monitors).

**Dashboard** shows the latest values as tiles, each with a line for its
last minute:

- Large readouts for RPM, speed, gear, coolant, and throttle.
- OBD-II PIDs, in groups. Pressures, which the PIDs report in kPa, are shown
  in bar (absolute).
- "From the CAN bus": signals identified from the car's own traffic but
  matched to no PID. See
  [broadcast CAN traffic](../../lib/device/docs/can-broadcast.md#inferred-signals).
- UDS data identifiers matched to a PID, each over the polled value it
  matches, when the source has DIDs.

What the tiles are, and their labels, units, and groups, comes from the
server's `/api/catalog`, not from this code: a signal added to the device
library's `inferred-signals`, or a PID added to its poll schedule, appears
without a change here. A PID or signal the catalog doesn't describe goes in
an Other group once it arrives. Groups flow across the page, each as wide as
its tiles, and each section folds away (remembered in the browser).

Badges say how a value is known, the same on tiles and in tables: **polled**
(a mode 01 PID), **CAN** (also decoded from the bus and confirmed against
the PID), **inferred** (from the bus, not confirmed), **fitted** (a DID
with a scale fitted to a PID), and **published** (a DID named and scaled in a
list published for related Subaru ECUs, checked against drives). A count with no known scale has the unit "raw". A
tile whose catalog entry has an `:override` shows its text while the signal
it names is true: the gear shows R while reverse is on.

**Signals** lists every PID, CAN signal, and DID in tables, with the badges'
legend.

**Bus** shows the latest frame of every CAN ID, with how often it comes and
what is decoded from it. Each decoded value has a colour: a bar under the
frame's bytes marks the bits it is decoded from, and the bit grid of the ID
you click fills those bits and lists each value with its bits (`b2,
b3.5–0`) and what it reads now. The bits come from the catalog's `:bits`.
A byte is highlighted as it changes. To find what a
control does: let the bus run for a few seconds, press Mark baseline, and
operate the control. Bits that had not changed before the baseline are
listed by name (`374 b3.0`, as in the docs) and filled in the bit grid of
the ID you click. The server reports changes shorter than its 100 ms
batches, so a 20 ms pulse still shows.

**Recordings** lists the saved recordings, grouped by the vehicle each was
recorded on (by calibration ID), with Map and Replay buttons for each. See
[recordings](../../lib/device/docs/recordings.md#recording-from-the-dashboard).

**Map** bins one value by two others, like
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
  that part. Clicking a cell shows its details beside the table and marks
  its rows on the timeline.

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
| `events` | App state: connection, source status and info, the catalog, latest sample per PID and value per inferred signal, CAN frames, replay position, adapters |
| `subs` | Subscriptions |
| `views` | The source bar, the Dashboard, Signals, and Recordings tabs |
| `bus-view` | The Bus tab |
| `map-view` | The Map tab: controls, filters, timeline, heat table, and cell details |
| `format` | Units, number formats, a tile's value as text, and labels from the catalog |
| `catalog` | Pure: turns the server's catalog into sections, groups, and tiles, and into the fields of each CAN ID |
| `bus` | Pure: the latest frame per CAN ID, which bits have changed, the baseline, and which field a bit belongs to |
| `series` | Pure: time-series logs from recordings or the stream, holding values on a grid, axes, binning, and sparklines |

`catalog`, `bus`, and `series` are tested by `npm test`.
