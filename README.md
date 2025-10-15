# AttendanceMVP

A lightweight Android app that records student attendance using device geolocation at scheduled times (entrée / pause / sortie) and syncs to a backend.

## Why this exists
- Automate presence tracking with GPS
- Validate presence inside school geofence
- Provide daily KPIs (presence / late / absence)

## Current status
- Android app skeleton started
- Background location flow & scheduling in progress
- Backend/API, geofence checks, and dashboard not yet implemented

## Architecture (high level)
- **Android app**: WorkManager schedules check-ins; uses FusedLocationProvider to get location with timeout/backoff; stores locally; syncs when online.
- **Backend (planned)**: REST API `POST /checkins` → PostGIS/ArcGIS for geofence validation.
- **Dashboard (planned)**: daily presence KPIs + map.

## Permissions
- `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION`
- `ACCESS_BACKGROUND_LOCATION` (only if automated background check-ins are required)
- Android 14+: uses a Foreground Service (foregroundServiceType=location) during background acquisition

## Build
- Android Studio Ladybug+ (AGP 8.x), JDK 17
- `compileSdk = 34`, `minSdk = 26`, `targetSdk = 34`
- Run: `./gradlew assembleDebug`

## Scheduled check-ins
- Morning (entrée)
- Midday (pause)
- Afternoon (sortie)

Each window enqueues a Worker that:
1) Ensures permissions granted & location enabled
2) Promotes to foreground on Android 14+ while getting location
3) Calls `getCurrentLocation()` (timeout ~15s) → fallback `lastLocation`
4) Stores result (timestamp, lat, lng, accuracy, provider)
5) Retries with exponential backoff if needed

## Local data (app)
- `CheckIn(id, ts, lat, lng, accuracy, provider, status=local|synced)`

## API (planned)
- `POST /checkins` → `{ studentId, ts, lat, lng, accuracy, deviceId, appVersion }`

## Roadmap
- [ ] Permission UX + BG location (Android 10–14)
- [ ] WorkManager windows (entrée/pause/sortie)
- [ ] Offline queue + exponential backoff
- [ ] Backend + PostGIS geofence check
- [ ] Dashboard (presence/late/absence + map)
- [ ] Alerts/reports + training materials

## Security & privacy
- No keystores or secrets in repo
- Explain location use in app privacy text and store listing
