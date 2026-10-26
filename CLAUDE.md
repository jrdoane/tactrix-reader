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
clojure -M:run <command>        # CLI; run with no matching command for usage

# services/engine-server
clojure -M:test
clojure -M:run                  # replay the committed recording on :8080 (no hardware)
clojure -M:run live [path]      # poll a real vehicle
PEDESTAL_DEV_MODE=true clojure -M:run   # needed with `shadow-cljs watch` (CSP allows hot reload)

# ui/dashboard
npm install
npx shadow-cljs release app     # or `watch app`
```

Run a single test (the `:test` aliases run fixed namespaces through `-main`, so
use an inline alias instead):

```sh
clojure -Sdeps '{:aliases {:t {:extra-paths ["test"]}}}' -M:t \
  -e "(require 'clojure.test 'tactrix-reader.device-test) (clojure.test/run-test-var #'tactrix-reader.device-test/parses-hex-request-bytes)"
```

There is no linter or formatter configured. Tests use no hardware: they stub
serial I/O with `with-redefs` and fakes in `device_test.clj` such as
`fake-adapter` (an in-memory adapter that answers each `at...` command) and
`fake-vehicle-responder` (canned ECU replies for it).
Running the CLI or server against a device needs a real adapter, and vehicle
commands need a vehicle with the ignition on.

## Architecture

### Data flow

```
adapter --serial--> device.clj source --emit--> hub (core.async) --SSE/Transit--> re-frame db
```

1. **Sources** (`start-live-source!`, `start-replay-source!` in `device.clj`)
   call `:emit` with identical events: `{:type :sample :pid :name :value :unit :timestamp-us}`
   and `{:type :status :source ... :state :running|:stopped|:failed}`.
   Consumers must not depend on which source is running.
2. **Hub** (`engine_server/hub.clj`): batches samples every 100 ms, keeping
   only the latest per PID. It forwards status immediately and keeps a
   snapshot for new connections. Every channel uses a sliding buffer, so slow
   browsers drop data instead of back-pressuring the source.
3. **Server** (`engine_server/server.clj`): `/stream` sends a `snapshot` SSE
   event and then `samples` and `status` events, with Transit JSON data.
   `main.clj` wires the hub, source, and server together.
4. **Dashboard**: `stream.cljs` turns SSE events into `:stream/*` re-frame
   events. `events.cljs` stores samples keyed by `:name`, not by PID number.

The dashboard build writes into
`services/engine-server/resources/public/assets/js`, which is gitignored. The
server serves it from there.

### `lib/device/src/tactrix_reader/device.clj`

A single large namespace, layered roughly top to bottom:

- **Discovery and adapter checks:** `scan-devices` (via `ioreg`), `open-device!`, `read-vbat`, `vehicle-status`.
- **Protocol discovery helpers:** `serial-exchange`, `run-probe`, `observe-device`. They write only the bytes they are given.
- **OpenPort command layer:** `parse-adapter-stream` splits the serial stream into ASCII reply lines (`aro`, `are <code>`, ...) and binary received messages (`ar<chan><len>...`). Sessions send `at...` commands and wait for typed events. `openport-operations` maps J2534 operations (such as `:pass-thru-connect`) to their confirmed command prefixes (such as `"ato"`), and `known-operation?` and `unsupported-operation` read it. There is no general J2534 API yet.
- **Two ways of talking to the ECU:**
  - `obd-request` (and `read-pid` and `read-all-pids` on top of it) opens an ISO15765 channel (6) for each request. The adapter handles flow control and multi-frame reassembly.
  - `ssm-init` (ECU ID, SSM `AA`) and `ssm-read` (memory reads, SSM `A8`) use the same ISO15765 path, sharing `iso15765-request` with `obd-request`. The project's 2015 Impreza (calibration ID `EA1T002C`) does not answer SSM over CAN; see `lib/device/docs/subaru-ssm.md`.
  - `run-can-session!` keeps one raw CAN channel (5) open with a pass-all filter. It sends single-frame `02 01 <pid>` requests to `7E0` on `default-poll-schedule` and decodes `7E8` replies with `reply-sample`. `record` and the live source both use it.
- **PID decoding:** the `mode-01-pids` map (PID → name, byte count, unit, `:decode` fn). Add a PID there and in the table in `lib/device/docs/obd-pids.md`.
- **Recordings:** `raw.bin` is the source of truth. `recording-samples` and the replay source re-parse `raw.bin` and take the reply ID from `meta.edn`. They do not read `pids.csv`. The CSVs are derived by `export-recording`, and `frames.csv` is gitignored.

The CLI (`device/cli.clj`) is a thin `case` over these functions. Its `usage`
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
- Don't send the adapter's `atv` command: it may set programming voltage on a
  pin.
- Protocol discovery writes only explicit bytes and never sweeps. Arbitrary
  `at...` bytes can transmit on the vehicle bus, so discovery is done with no
  vehicle connected.
- `lib/device/docs/openport-protocol.md` records adapter behavior that was
  observed, not documented by the vendor. Mark inferences as such when adding
  to it. Add a command to `openport-operations` only once probing confirms
  it, and update the doc's commands table along with the map.
