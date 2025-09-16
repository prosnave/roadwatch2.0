# RoadWatch Android — Server Integration Guide

This guide documents how to run the Android app against the RoadWatch backend (server‑integrated mode), how to configure the app, and the key end‑to‑end flows for MVP.

## Prerequisites

- Backend up and reachable (dev or prod)
  - Dev (SQLite):
    - `cp .env.example .env` and adjust values
    - `npm install`
    - `npm run migrate`
    - `npm run dev` (listens on `http://localhost:4000` by default)
  - Prod (Postgres):
    - Set `DATABASE_URL=postgres://…` in `.env`
    - `npm run migrate`, then run the server behind HTTPS
- For Android dev over HTTP:
  - AndroidManifest already has `android:usesCleartextTraffic="true"` for dev.
  - On emulator use `http://10.0.2.2:4000`; on device use your laptop LAN IP.

## App Configuration (Settings → Server)

- Base URL
  - Enter your API root (e.g. `http://10.0.2.2:4000` or `https://api.example.com`) and tap “Save URL”.
- Account (public flows)
  - Enter email + password → “Save Account”.
  - If new, tap “Create Account” to register with backend (`POST /v1/account/register`).
  - You can push/pull local preferences to/from your account (see Settings Sync below).
- Admin (admin flows)
  - Enter admin email + password and tap “Admin Login”.
  - Admin tokens auto‑refresh on expiry; tap “Logout” to clear.

## Key Flows (End‑to‑End)

- Drive Mode (consume server hazards)
  - Start Drive Mode; grant location permission.
  - App fetches nearby hazards from server and keeps a local cache warm via a periodic sync.
- Report Hazard (public)
  - From Drive HUD, use the report buttons (speed bump / pothole / rumble strip / speed limit zone).
  - The app calls `POST /v1/hazards` using your account (HTTP Basic) and shows success/duplicate messages.
- Voting (public)
  - Tap a hazard marker → “Add Vote”/“Remove Vote”.
  - Label adapts based on current vote status.
- Admin Manage Hazards
  - Open Admin Locations screen (admin flavor) after admin login.
  - “Server mode” shows server hazards only, with filters + pagination:
    - Filters: type, source, active only, text search, bounding box, created date range
    - Load More: uses cursor pagination
  - Actions: Toggle Active, Edit Type/Direction, Move Pin (update lat/lng), Delete, Add/Remove Vote.

## Settings Sync (Roaming Preferences)

- Save Settings to Account
  - In Settings, tap “Save Settings to Account” (Basic auth); writes preferences JSON to server.
- Load Settings from Account
  - Tap “Load Settings from Account”; applies server preferences locally.
- Synced keys include: audio/visual/haptics, background alerts, audio focus, speed curve, zone enter/exit messages and repeat interval, clustering toggle + speed threshold, default mute minutes.

## Notes & Troubleshooting

- Use HTTPS in staging/prod; CORS is enabled on backend and configurable via `CORS_ALLOWED_ORIGINS`.
- If you see 401 on admin actions, app automatically refreshes admin tokens; retry the action.
- Hazard create dedups same type within ~20 m. Zones require speed.limit and start/end points.
- Import seed hazards (server‑side): `npm run import:hazards -- data/seeds.csv`.

## Useful Backend Commands (from back‑end/)

- Install & run dev API:
  - `npm install`
  - `npm run migrate`
  - `npm run dev`
- Import hazards from CSV:
  - `npm run import:hazards -- data/your_hazards.csv`

---

If you want to run the self‑contained (offline) app instead of server‑integrated mode, switch to your offline branch, or clear Base URL/Account in Settings to fall back to local data where supported.

