# RoadWatch Android App

A comprehensive road hazard detection and alert system for Android drivers.

## Features

- **Real-time Hazard Detection**: Monitors road hazards ahead using GPS and speed data
- **Dual-carriageway Support**: Intelligent filtering for opposite-direction hazards
- **Voice & Vibration Alerts**: Customizable audio and haptic feedback
- **Interactive Map**: Google Maps integration with hazard markers
- **Speed-adaptive Warnings**: Alert distance adjusts based on driving speed (120-600m)
- **Offline Seed Data**: Pre-loaded with 60+ Nairobi road hazards
- **Admin Features**: Full hazard management (admin flavor only)

## Architecture

- **Language**: Kotlin
- **UI**: Jetpack Compose + Material 3
- **Maps**: Google Maps Compose
- **Data**: Room Database
- **DI**: Hilt
- **Async**: Coroutines + Flow
- **Location**: FusedLocationProvider
- **Audio**: TextToSpeech with audio focus

## Project Structure

```
com.roadwatch/
├── app/                 # Main activity and navigation
├── core/               # Shared services (location, TTS, audio)
├── data/               # Database, entities, repositories
├── domain/             # Business logic and use cases
└── feature/            # UI screens and ViewModels
    ├── home/          # Main screen
    ├── drive/         # Drive mode HUD
    ├── passenger/     # Hazard reporting
    ├── settings/      # Configuration
    └── locations/     # Admin hazard management
```

## Build Instructions

### Prerequisites

1. **Java 17**: Required for AGP 8.x

   ```bash
   java -version  # Should show Java 17
   ```

2. **Android SDK**: Install command line tools

   ```bash
   # macOS/Linux
   export ANDROID_SDK_ROOT=$HOME/Android/Sdk
   mkdir -p $ANDROID_SDK_ROOT

   # Download commandlinetools from:
   # https://developer.android.com/studio#command-tools

   $ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager \
     "platform-tools" \
     "platforms;android-35" \
     "build-tools;35.0.0" \
     "emulator" \
     "system-images;android-35;google_apis;x86_64"
   ```

3. **Google Maps API Key**: Get from Google Cloud Console
   - Enable Maps SDK for Android
   - Enable Places API
   - Create API key with restrictions
   - Add to `local.properties`:
     ```
     MAPS_API_KEY=your_api_key_here
     ```

### Building

```bash
# Clean build
./gradlew clean

# Build public flavor
./gradlew assemblePublicDebug

# Build admin flavor
./gradlew assembleAdminDebug

# Build both release versions
./gradlew assemblePublicRelease assembleAdminRelease
```

### Output

APKs will be generated in:

- `app/build/outputs/apk/public/debug/`
- `app/build/outputs/apk/admin/debug/`

## Local Development Server

To serve APKs for testing:

```bash
# Start Python server on port 8080
python3 -m http.server 8080

# Or with custom port
python3 server.py 8080
```

Access APKs at:

- `http://localhost:8080/app/build/outputs/apk/public/debug/app-public-debug.apk`
- `http://localhost:8080/app/build/outputs/apk/admin/debug/app-admin-debug.apk`

## Permissions Required

```xml
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.VIBRATE" />
```

## Key Components

### ProximityAlertEngine

- Handles corridor-based hazard detection
- Speed-adaptive alert distances
- Dual-carriageway bearing filtering
- 5-minute suppression to prevent spam

### DriveModeService

- Foreground location service
- 1-second update intervals
- Speed and bearing calculation
- Battery-optimized location tracking

### TextToSpeechManager

- Audio focus management
- Vibration integration
- Customizable alert messages
- DataStore preferences

## Testing

```bash
# Unit tests
./gradlew testDebugUnitTest

# Instrumented tests (requires device/emulator)
./gradlew connectedDebugAndroidTest

# All tests
./gradlew testDebugUnitTest connectedDebugAndroidTest
```

## Seed Data

Pre-loaded with Nairobi road hazards:

- Speed bumps
- Rumble strips
- Potholes
- Police checkpoints

Data stored in `app/src/main/assets/seeds.csv`

## Configuration

### Build Variants

- **Public**: Standard user features
- **Admin**: Includes hazard management

### Runtime Configuration

- Voice alerts toggle
- Vibration alerts toggle
- Alert distance multiplier (0.5x - 2.0x)
- Test alert functionality

## Architecture Decisions

1. **Clean Architecture**: Separation of data, domain, and presentation layers
2. **Reactive Programming**: Kotlin Flow for real-time data streams
3. **Dependency Injection**: Hilt for testable and maintainable code
4. **Material 3**: Modern Android design system
5. **Foreground Service**: Reliable background location tracking
6. **Room Database**: Type-safe SQLite wrapper with migrations

## Performance Optimizations

- Spatial indexing for hazard queries
- Coroutine-based async operations
- Efficient location update intervals
- Battery-aware service management
- Memory-efficient data structures

## Security Considerations

- Location permissions with rationale dialogs
- API key restrictions in Google Cloud Console
- No sensitive data storage
- Secure service communication

## Future Enhancements

- Offline map tiles
- Hazard photo attachments
- Social hazard sharing
- Route-based predictions
- Integration with Waze/Google Maps
- Advanced analytics and reporting
