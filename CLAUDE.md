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
clojure -M:run                  # replay the committed recording on :8080 (no hardware)
clojure -M:run live [path]      # poll a real vehicle
PEDESTAL_DEV_MODE=true clojure -M:run   # needed with `shadow-cljs watch` (CSP allows hot reload)

# ui/dashboard
npm install
npx shadow-cljs release app     # or `watch app`
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
   signals, and `{:type :status :source ... :state :running|:stopped|:failed}`.
   Consumers must not depend on which source is running.
2. **Source manager** (`engine_server/sources.clj`): an agent that switches
   between replay, a live adapter, and no source, one switch at a time. Each
   switch stops the old source, emits `{:type :reset}`, and emits a
   `{:type :info ...}` event (adapter, vehicle identity from
   `read-vehicle-info` and trouble codes from `read-trouble-codes`, or
   recording metadata) before starting the new one.
3. **Hub** (`engine_server/hub.clj`): batches samples and signals every
   100 ms, keeping only the latest per PID and per signal. It forwards status and info immediately and keeps
   a snapshot for new connections; `:reset` clears it and sends an empty
   snapshot. Every channel uses a sliding buffer, so slow browsers drop data
   instead of back-pressuring the source.
4. **Server** (`engine_server/server.clj`): `/stream` sends a `snapshot` SSE
   event and then `samples`, `status`, and `info` events, with Transit JSON
   data. `GET /api/adapters` and `POST /api/source` let the dashboard pick an
   adapter; `local-api-guard` limits them to local, Transit requests, and only
   paths from `scan-devices` are accepted. `main.clj` wires the hub, manager,
   and server together.
5. **Dashboard**: `stream.cljs` turns SSE events into `:stream/*` re-frame
   events and `http.cljs` calls the API. `events.cljs` stores samples keyed by
   `:name`, not by PID number, and signals keyed by `:signal`. `views.cljs`
   tags inferred signals as inferred and PIDs also decoded from CAN as CAN.

The dashboard build writes into
`services/engine-server/resources/public/assets/js`, which is gitignored. The
server serves it from there.

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
  - `iso15765/iso15765-request` opens an ISO15765 channel (6) for each request. The adapter handles flow control and multi-frame reassembly. `obd/obd-request` (and `read-pid`, `read-all-pids`, `read-vehicle-info`, and `read-trouble-codes` on top of it, also in `obd`) uses it.
  - `ssm/ssm-init` (ECU ID, SSM `AA`) and `ssm/ssm-read` (memory reads, SSM `A8`) use the same ISO15765 path. `obd/read-vehicle-info` calls `ssm-init` for the ECU ID. The project's 2015 Impreza (calibration ID `EA1T002C`) does not answer SSM over CAN; see `lib/device/docs/subaru-ssm.md`.
  - `uds/read-did` sends UDS ReadDataByIdentifier (service `22`) on the same ISO15765 path and returns the data undecoded. It is being tried as a route to knock correction on that car.
  - `can/run-can-session!` keeps one raw CAN channel (5) open with a pass-all filter. It sends single-frame `02 01 <pid>` requests to `7E0` on `default-poll-schedule` and decodes `7E8` replies with `reply-sample`. `record` and the live source both use it.
- **Broadcast decoding (`broadcast`):** `broadcast-signals` maps CAN IDs from the car's own traffic to the mode 01 PID each signal matches, and `broadcast-samples` decodes them into PID-shaped samples with `:can-id`. The live and replay sources emit them alongside polled PIDs (`:broadcast {}` turns them off). Add a signal only once it matches a polled PID, and record it in `lib/device/docs/can-broadcast.md`.
- **Inferred signals (`broadcast`):** `inferred-signals` holds signals identified only from how they behave (gear, steering angle, brake, wheel speeds, accelerations, and others), which match no PID. `signal-samples` decodes them, and the sources emit them as `:signal` events (`:signals {}` turns them off). Keep them out of `broadcast-signals`; move one there only if it comes to match a polled PID. Record each in `lib/device/docs/can-broadcast.md`.
- **`recording`:** `raw.bin` is the source of truth. `recording-samples` and the replay source re-parse `raw.bin` and take the reply ID from `meta.edn`. They do not read `pids.csv`. The CSVs are derived by `export-recording`, and `frames.csv` is gitignored.
- **`source`:** the live and replay sources, built on `can` and `recording`.

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
