# Engine dashboard

ClojureScript front end built with re-frame, Reagent 2, and React 19. It
connects to the [engine server's](../../services/engine-server/README.md)
`/stream` endpoint and shows live engine data.

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
| `events` | App state: connection, source status, latest sample per PID |
| `subs` | Subscriptions |
| `views` | Page: RPM and coolant readouts and a table of every value |
