# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A prototype that reads engine data from a Tactrix OpenPort 2.0 over USB on
macOS. It writes the adapter's own serial commands to `/dev/cu.usbmodem*`,
with no vendor J2534 library. It is a monorepo of three components, each with
its own build. Run commands from the component's directory:

- `lib/device`: Clojure library and CLI (tools.deps)
- `services/engine-server`: Pedestal server; depends on `lib/device` via `:local/root`
- `ui/dashboard`: ClojureScript dashboard (shadow-cljs, re-frame)

## Commands

```sh
# lib/device
clojure -M:test                 # all tests
clojure -M:lint                 # clj-kondo (also in engine-server)
clojure -M:run <command>        # CLI; run with no matching command for usage

# services/engine-server
clojure -M:test
clojure -M:run                  # replay the newest recording in ~/.tactrix-reader/recordings on :8080 (no hardware)
clojure -M:run live [path]      # poll a real vehicle
PEDESTAL_DEV_MODE=true clojure -M:run   # needed with `shadow-cljs watch` (CSP allows hot reload)

# ui/dashboard
npm install
npx shadow-cljs release app     # or `watch app`
npm test                        # node tests of the pure namespaces (`series`, `catalog`, `bus`)
npm run lint                    # clj-kondo
```

Run a single test (the `:test` aliases run fixed namespaces through `-main`, so
use an inline alias instead):

```sh
clojure -Sdeps '{:aliases {:t {:extra-paths ["test"]}}}' -M:t \
  -e "(require 'clojure.test 'tactrix-reader.device.codec-test) (clojure.test/run-test-var #'tactrix-reader.device.codec-test/parses-hex-request-bytes)"
```

clj-kondo uses the shared `.clj-kondo/config.edn` at the repo root, which
turns on some opt-in linters (sorted requires, unused aliases, redundant
calls, and others). `:shadowed-var` stays off, because locals such as
`bytes` and `name` shadow core vars by design. There is no formatter.

Tests use no hardware: they stub serial I/O with `with-redefs` and fakes in
`device/test_support.clj` such as `fake-adapter` (an in-memory adapter that
answers each `at...` command) and `fake-vehicle-responder` (canned ECU replies
for it). Each device namespace has a matching `<name>_test.clj`; add new test
namespaces to `device_test_runner.clj`. Redefine a var in the namespace that
defines it, such as `openport/open-session` or `serial/serial-exchange`.
Running the CLI or server against a device needs a real adapter, and vehicle
commands need a vehicle with the ignition on.

## Architecture

### Data flow

```
adapter --serial--> device.source --emit--> hub (core.async) --SSE/Transit--> re-frame db
```

1. **Sources** (`start-live-source!`, `start-replay-source!` in `device/source.clj`)
   call `:emit` with identical events: `{:type :sample :pid :name :value :unit :timestamp-us}`,
   `{:type :signal :signal :value :unit :can-id :timestamp-us}` for inferred CAN
   signals (and, with no `:can-id`, for the values `derived` works out from
   the rest, such as engine power), `{:type :did :did :did-hex :data :timestamp-us}` for single-frame
   UDS service 22 replies (only recordings made with `record-dids` have them;
   `uds/did-sample` adds `:name`, `:matches`, `:value`, and `:unit` for the DIDs
   in `uds/did-signals`; the live source polls them with `:dids`), and
   `{:type :status :source ... :state :running|:stopped|:failed}`. With
   `:frames? true` (which the server passes) they also emit
   `{:type :frame :id :data :timestamp-us}` for every CAN frame. The live
   source's `:start-recording!`/`:stop-recording!` save what it receives as a
   recording and emit `{:type :recording :state :recording|:saved ...}`. The
   replay source's `:control!` pauses, resumes, seeks, and sets the speed,
   and with `:position-ms` it emits `{:type :position ...}`; a seek shifts
   emitted timestamps, as a loop does, so they always increase, and first
   emits the ten seconds before its target at once, so the latest values
   are right there even while paused.
   Consumers must not depend on which source is running.
2. **Source manager** (`engine_server/sources.clj`): an agent that switches
   between replay, a live adapter, and no source, one switch at a time. Each
   switch stops the old source, emits `{:type :reset}`, and emits a
   `{:type :info ...}` event (adapter, vehicle identity from
   `read-vehicle-info` and trouble codes from `read-trouble-codes`, or
   recording metadata) before starting the new one. It also starts and stops
   recordings of the live source, into the configured recordings directory,
   with the identified vehicle in `meta.edn`; a switch saves any recording
   first. `control-replay!` passes a command to the replay source.
3. **Hub** (`engine_server/hub.clj`): batches samples, signals, DID
   replies, and CAN frames every 100 ms, keeping only the latest per PID,
   signal, DID, and CAN ID. A batched frame has a `:changed` mask per byte of
   the bits that changed between any two frames in the batch, so the
   dashboard's bus view sees a change shorter than a batch. It forwards status, info, recording, and position events immediately and keeps
   a snapshot for new connections; `:reset` clears it and sends an empty
   snapshot. Every channel uses a sliding buffer, so slow browsers drop data
   instead of back-pressuring the source.
4. **Server** (`engine_server/server.clj`): `/stream` sends a `snapshot` SSE
   event and then `samples`, `status`, `info`, `recording`, and `position`
   events, with Transit JSON data. `GET /api/catalog` gives the device
   library's `catalog/catalog`, which the dashboard builds its tiles from.
   `GET /api/adapters`, `GET /api/recordings`,
   `POST /api/source`, `POST /api/recording`, and `POST /api/replay` let the
   dashboard pick an adapter or recording, record, and pause or seek a
   replay, and `GET /api/recordings/:name/series`
   gives a recording's values as time series for the map (the last one is
   cached; a long drive takes seconds to decode); `local-api-guard` limits them to local,
   Transit requests, and only paths from `scan-devices` and names from
   `list-recordings` are accepted. `main.clj` wires the hub, manager,
   and server together.
5. **Dashboard**: `stream.cljs` turns SSE events into `:stream/*` re-frame
   events and `http.cljs` calls the API. `events.cljs` stores samples keyed by
   `:name`, not by PID number, signals keyed by `:signal`, DID replies
   keyed by `:did`, with the range each has read, and CAN frames keyed by
   ID. It has five tabs (Dashboard, Signals, Bus, Map, Recordings) under one
   source bar. The tiles, their labels, units, and groups come from
   `/api/catalog` through `catalog.cljs`, not from lists in the views; a
   name the catalog lacks goes in an Other group. `views.cljs`
   tags inferred signals as inferred, PIDs also decoded from CAN as CAN,
   and the derived values, in their own section, as derived.
   The Bus tab (`bus_view.cljs`, `bus.cljs`) shows each CAN ID's latest frame
   and which bits are new since a marked baseline, and colours the bits each
   value is decoded from, which the catalog gives as `:bits`.
   The map view (`map_view.cljs`) bins one value by two others, such as timing
   by RPM and load (MAF × 60 / RPM, g/rev), for a recording's series or for
   the stream, which `events.cljs` also appends to a log of the same shape.
   `series.cljs` holds the pure parts (that log, holding values on a 100 ms
   grid, axes, binning, and the tiles' sparklines); its
   `event-points` repeats the device library's `series/event-points`, so
   change both together. `series.cljs`, `catalog.cljs`, and `bus.cljs` are
   pure and are the namespaces with tests.

The dashboard build writes into
`services/engine-server/resources/public/assets/js`, which is gitignored. The
server serves it from there.

Configuration and recordings live outside the repo, in `~/.tactrix-reader`
(see `config` below). Tests never touch it: they pass a temporary
`:recordings-dir` or redefine `config/home-dir`.

### `lib/device/src/tactrix_reader/device/`

Namespaces under `tactrix-reader.device`, roughly from the lowest layer up:

- **`codec`:** hex parsing and formatting and big-endian integers, shared by the rest.
- **`usb`:** `scan-devices` (via `ioreg`), `open-device!`.
- **`serial`:** raw stream reads, plus the protocol discovery helpers `serial-exchange` and `observe-device`. They write only the bytes they are given.
- **`adapter`:** adapter-local checks that send no bus traffic: `read-vbat`, `vehicle-status`, `direct-backend-info`.
- **`openport`:** the OpenPort command layer. `parse-adapter-stream` splits the serial stream into ASCII reply lines (`aro`, `are <code>`, ...) and binary received messages (`ar<chan><len>...`). Sessions send `at...` commands and wait for typed events. `openport-operations` maps J2534 operations (such as `:pass-thru-connect`) to their confirmed command prefixes (such as `"ato"`), and `known-operation?` and `unsupported-operation` read it. There is no general J2534 API yet.
- **`probe`:** `run-probe` wraps `serial-exchange` as a named discovery transcript.
- **`pids`:** the `mode-01-pids` map (PID → name, byte count, unit, `:decode` fn) and `decode-mode-01`. Add a PID there and in the table in `lib/device/docs/obd-pids.md`.
- **Two ways of talking to the ECU:**
  - `iso15765/iso15765-request` opens an ISO15765 channel (6) for each request, unless it is given a channel from `iso15765/with-channel`, which opens the device and connects once, at the first request, and shares that among every request made with it. The adapter handles flow control and multi-frame reassembly. `obd/obd-request` (and `read-pid`, `read-all-pids`, `read-vehicle-info`, and `read-trouble-codes` on top of it, also in `obd`) uses it. The functions that send several requests, including `uds/scan-dids` and `uds/supported-dids`, and the server's identify step each use one channel.
  - `ssm/ssm-init` (ECU ID, SSM `AA`) and `ssm/ssm-read` (memory reads, SSM `A8`) use the same ISO15765 path. `obd/read-vehicle-info` calls `ssm-init` for the ECU ID. The project's 2015 Impreza (calibration ID `EA1T002C`) does not answer SSM over CAN; see `lib/device/docs/subaru-ssm.md`.
  - `uds/read-did` sends UDS ReadDataByIdentifier (service `22`) on the same ISO15765 path and returns the data undecoded, `uds/scan-dids` reads every DID in an explicit range, and `uds/supported-dids` follows the ECU's supported-DID bitmaps (at `1000`, `1020`, ...). The Impreza answers service `22`; the DIDs it exposes, and the few identified from a drive, are recorded in `lib/device/docs/subaru-ssm.md`.
  - `can/run-can-session!` keeps one raw CAN channel (5) open with a pass-all filter. It sends single-frame `02 01 <pid>` requests to `7E0` on `default-poll-schedule`, one at a time: each once the last is answered on `7E8` or `default-reply-timeout-ms` passes, and at least `default-poll-interval-ms` after it. It decodes the replies with `reply-sample`. `record` and the live source both use it. With `:dids` it alternates them with single-frame service 22 requests, decoded by `did-reply`; `record-dids` uses this, and `export-recording` writes the replies to `dids.csv`.
- **Broadcast decoding (`broadcast`):** `broadcast-signals` maps CAN IDs from the car's own traffic to the mode 01 PID each signal matches, and `broadcast-samples` decodes them into PID-shaped samples with `:can-id`. The live and replay sources emit them alongside polled PIDs (`:broadcast {}` turns them off). Add a signal only once it matches a polled PID, and record it in `lib/device/docs/can-broadcast.md`.
- **Inferred signals (`broadcast`):** `inferred-signals` holds signals identified only from how they behave (gear, clutch, reverse, steering angle, brake, wheel speeds, accelerations, engine torque, doors, and others), which match no PID. `signal-samples` decodes them, and the sources emit them as `:signal` events (`:signals {}` turns them off). Keep them out of `broadcast-signals`; move one there only if it comes to match a polled PID. Record each in `lib/device/docs/can-broadcast.md`.
- **`recording`:** `raw.bin` is the source of truth. `recording-samples` and the replay source re-parse `raw.bin` and take the reply ID from `meta.edn`. They do not read `pids.csv`. The CSVs are derived by `export-recording`. `start-recording!` writes `raw.bin` and `meta.edn` (first with `:in-progress? true`) for both `record` and the live source, and `list-recordings` describes a recordings folder.
- **`config`:** `~/.tactrix-reader` (or `$TACTRIX_READER_HOME`) holds an optional hand-edited `config.edn` (`:recordings-dir`, `:vehicles` descriptions keyed by VIN or calibration ID) and, by default, `recordings/`. `resolve-recording-dir` treats a bare name as a folder there and anything with `/` as a path. Recordings are local data and are not committed.
- **`derived`:** values worked out from decoded ones, not read from the car: engine power, fuel economy, road grade, clutch slip, and injector duty. `step` is pure (`[state event]` to `[state events]`), and `deriver` wraps it for a stream. The sources and `series/recording-series` pass every decoded event through one, so the derived values follow as `:signal` events (`:derived? false` turns them off in a source). `derived-signals` describes each for the catalog. The gearing and other constants are this car's, measured in its recordings. Each value comes at most 20 times a second, and the state starts again when time jumps, as when a replay loops or seeks. Record a new one in `lib/device/docs/can-broadcast.md`.
- **`source`:** the live and replay sources, built on `can`, `recording`, and `derived`.
- **`catalog`:** `catalog` describes how to show every value, as plain data
  with no functions: sections, groups, the primary values, and an entry for
  each PID, inferred signal, and DID. PIDs get their label and group in
  `pid-display`; inferred signals and DIDs carry theirs (`:label`, `:group`,
  `:format`, `:raw?`, `:parts`, `:override`, `:compare`, `:published?`) in `broadcast/inferred-signals`
  and `uds/did-signals`, and `uds/did-notes` holds what is known about the
  other DIDs. Give a new signal a `:label` and `:group` there; without them
  it is labelled from its name and shown under Other. An entry decoded from
  the bus also has `:bits`, a mask per byte of the frame bits it reads.
  `broadcast/decode-bits` works them out from the `:decode` fn by flipping
  each bit, so they are not written down anywhere. Each of
  `derived/derived-signals` gets a `:kind :signal` entry with `:section
  :derived`, which puts it in the Worked out section, and `:from`.
- **`series`:** `recording-series` decodes a recording, as the replay source does (derived values included), into per-channel time series for the dashboard's map: keys `:pid/<name>`, `:signal/<signal>`, and `:did/x<hex>`, map values split into `key.part` channels, booleans as 1/0, other non-numbers as nil, and at most one point per channel per 100 ms, matching the hub's batches.

The CLI (`device/cli.clj`) is a thin `case` over these namespaces. Its `usage`
map should stay in sync with the command table in `lib/device/README.md`.

### Conventions

- Device functions return EDN maps with `:ok?` and an `:error` keyword instead
  of throwing, so a failed read is distinguishable from "vehicle absent". Keep
  that shape for new functions.
- Functions that take a device accept either a path string or a device map from
  `scan-devices`.

## Vehicle safety

- Only read-only OBD services are sent (`read-only-obd-services`). Never send
  service 04 (clear DTCs) or 08 (control on-board systems).
- The only SSM commands sent are `AA` (identify the ECU) and `A8` (read
  addresses). Never send SSM `B0` or `B8`: they write ECU memory.
- The only UDS service sent is `22` (ReadDataByIdentifier). Never send `2E`,
  `3D`, `31`, `11`, `10`, or `27`: they write data or memory, run routines,
  reset the ECU, or change session or security state.
- Don't send the adapter's `atv` command: it may set programming voltage on a
  pin.
- Protocol discovery writes only explicit bytes and never sweeps. Arbitrary
  `at...` bytes can transmit on the vehicle bus, so discovery is done with no
  vehicle connected.
- `lib/device/docs/openport-protocol.md` records adapter behavior that was
  observed, not documented by the vendor. Mark inferences as such when adding
  to it. Add a command to `openport-operations` only once probing confirms
  it, and update the doc's commands table along with the map.
