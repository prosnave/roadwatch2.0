# RoadWatch — Android app

Warns drivers about hard-to-see road hazards (speed bumps, rumble strips, potholes, speed-limit zones) before they reach them, using GPS and a crowd-sourced hazard list. Anyone in the car can report a hazard with one tap.

**Full documentation is in [`../docs/`](../docs/00-HANDOVER.md) — start with [`00-HANDOVER.md`](../docs/00-HANDOVER.md).**

## Quick start

```bash
# JDK 17 + Android SDK required; local.properties needs sdk.dir and MAPS_API_KEY (git-ignored)
./gradlew :app:testPublicDebugUnitTest      # unit tests (no device needed)
./gradlew :app:assemblePublicDebug          # APK: app/build/outputs/apk/public/debug/
./scripts/build-field-apk.sh                # tests + versioned APK in dist/ + signing check (what you send the tester)
```

Flavors: `public` (`com.roadwatch.app`) and `admin` (`com.roadwatch.app.admin`, hazard editing tools).

## Layout

```
app/src/main/java/com/roadwatch/
  engine/               pure-Kotlin alert engine (no Android imports)  ← start here for logic
  core/location/        DriveModeService – owns GPS, engine, alert delivery
  alerts/delivery/      voice (ducking), banner over other apps, presenter
  drive/                DriveSession (observable state), ReportActions, SimulatedDrive
  data/                 HazardStore (cached CSV), HazardMerge, HazardCsv, transfer/export
  trip/                 trip recording format, writer, summary, field zip
  feature/              home, drive (HUD+map), settings, fieldtest, admin, report (zones)
  prefs/ notifications/ ui/ app/
app/src/test/           43 JVM tests incl. real-dataset and trip-replay tests
app/src/main/assets/seeds.csv   tester's field dataset, imported once on first run into an empty store
```

Legacy scripts in this folder (`server.py`, `logging_server.py`, `send_log.py`, `receive_log.py`, `create-release*.sh`, `serve-release.py`, `capture-build-ref.sh`, `setup.sh`, `my-working-android-build-ref/`) belong to the original developer's workflow and are **superseded** by `scripts/build-field-apk.sh` and the in-app trip logs. They are slated for removal ([roadmap backlog](../docs/04-roadmap.md)).
