# FilamentVision Web

Desktop monitoring companion for the ENG5105 FilamentVision system. The frontend is now self-contained under `webiste2.0/frontend`; it has no runtime dependency on the former sibling `website` directory.

## Run locally

```bash
npm install
npm run dev
```

During development, Vite serves `http://127.0.0.1:5173` and proxies REST and WebSocket requests under `/api` to the launcher backend at `http://127.0.0.1:18080`. Set `FILAMENTVISION_BACKEND_URL` when starting Vite manually against a different backend address. The backend itself still supports its normal configurable `--port` option; the launcher uses 18080 to avoid Windows services that commonly reserve 8080.

From the project root, `start-website.bat` starts both services and opens the page after the frontend and proxied backend API respond. `stop-website.bat` stops only the process trees recorded for this project.

## Verification

```bash
npm test
npm run lint
npm run build
```

## Architecture

`MockFilamentDataSource` remains the current UI data-source implementation and implements the transport-neutral `FilamentDataSource` boundary. `MonitoringStore` owns immutable live, alarm, and configuration snapshots; React consumes focused channels with `useSyncExternalStore`. `MonitoringRuntime` protects producer and subscription lifecycle under React Strict Mode. The migration intentionally does not change this business behaviour.

The default absolute deviation thresholds mirror the Android application:

- Warning: `0.012 mm`
- Out of tolerance: `0.025 mm`

`OUT_OF_TOLERANCE` is the shared domain term. The dashboard displays “Critical” only as user-facing severity copy.

Recent Trend retains at most 300 transient samples. Alarm History retains at most 300 episode-ready events with start and recovery timestamps. Settings are currently persisted through a replaceable local-storage adapter.
