# GitHub Copilot Instructions

This is a voice-first Hong Kong transit helper app for smart glasses, built with the Universal Glasses SDK (xg-glass).

## Build, Install, and Run

**Build:**
```bash
./gradlew :app:assembleDebug
# or using xg-glass CLI:
xg-glass build
```

**Install & Run:**
```bash
xg-glass install
xg-glass run
```

**Debug script (recommended):**
```bash
scripts/debug-app.sh              # Full build + install + run + logcat
scripts/debug-app.sh build        # Build only
scripts/debug-app.sh install      # Install only
scripts/debug-app.sh run          # Run only
scripts/debug-app.sh logs         # Tail logcat
scripts/debug-app.sh crash        # Show crash logs
scripts/debug-app.sh clean-install # Uninstall + reinstall + run
```

**Lint:**
```bash
./gradlew lint
```

**No test suite exists.**

## Architecture

Two-module Android project with a clean separation between device-specific UI and device-agnostic business logic:

### Module Structure

- **`app/`** — Host Android app
  - `MainActivity.kt`: Device selection UI (Rokid, Meta, RayNeo/Mercury, Frame)
  - Manages glasses client lifecycle and connection state
  - Handles runtime permissions (location, Bluetooth)
  - GPS-based district detection for weather
  - Settings persistence via SharedPreferences
  - Runtime configuration UI for AI API credentials and Rokid auth

- **`ug_app_logic/`** — Device-agnostic business logic
  - `HkRouterPlannerEntry.kt`: Main entry point implementing `UniversalAppEntrySimple`
  - Returns `UniversalCommand` objects for voice-activated features
  - Pure Kotlin logic with no Android UI dependencies
  - Works across all supported glasses devices

### Voice Commands (in `HkRouterPlannerEntry.kt`)

1. **Weather Report** — GPS district detection → HKO weather API → displays current conditions and forecast
2. **Plan My Route** — Whisper STT for destination → ChatGPT structured extraction → DFS route search → KMB ETA API for live arrivals
3. **Live Bus ETA** — Whisper STT for bus number → KMB ETA API → displays next arrivals

### External APIs

- **OpenAI** (Whisper STT + ChatGPT): API key/base URL/model configured in host app Settings at runtime
- **HKO** (weather): `https://data.weather.gov.hk/weatherAPI/opendata/`
- **KMB ETA**: `https://data.etabus.gov.hk/`
- **hkbus.app route database**: `https://data.hkbus.app/routeFareList.min.json`

### Route Search Algorithm

DFS with max depth 2 (1 transfer), 500m walking radius to stops, using the hkbus.app route/stop JSON database loaded into memory.

### GPS-Based District Detection

User coordinates matched to nearest HKO weather station using multi-strategy fallback:
1. Exact station name match
2. GPS distance calculation
3. Administrative boundary

District center coordinates are hardcoded in `HkRouterPlannerEntry.kt`.

## Configuration

**`xg-glass.yaml`** — Project config specifying SDK path, entry class, module, and applicationId. Generated/updated by `xg-glass init`.

**`local.properties`** (not committed) — Contains:
- `sdk.dir`: Android SDK path (required)
- `rokid.clientSecret`: Optional Rokid auth (fallback if not entered in UI)
- `rokid.snRawName`: Optional Rokid SN file name without extension (fallback)

**Environment variables (optional):**
- `GITHUB_TOKEN`: GitHub packages token (`read:packages` scope) required for Meta AI glasses support
- `ROKID_CLIENT_SECRET`, `ROKID_SN_RAW_NAME`: Rokid auth fallback if not in `local.properties`
- `ADB`, `EMULATOR_BIN`, `AVD_NAME`, `PACKAGE_NAME`: Override defaults in `debug-app.sh`

## Conventions

### Universal Glasses SDK Integration

The Universal Glasses SDK (`xg-glass`) provides cross-device support from a single codebase. Key concepts:

- **Device selection at runtime**: User selects device type in host UI (Rokid, Meta, RayNeo, Frame)
- **`UniversalAppEntry`**: Interface for defining app commands and settings
- **`UniversalCommand`**: Encapsulates voice-activated commands with `run()` method
- **`UniversalAppContext`**: Provides access to glasses client, settings, and user data
- **`GlassesClient`**: Abstraction for device-specific display/audio/sensor APIs

### Module Boundaries

- **`app/` depends on**: `ug_app_logic`, Universal Glasses SDK device implementations, Android APIs
- **`ug_app_logic/` depends on**: Only `app-contract` from Universal Glasses SDK (device-agnostic interfaces)

This separation allows business logic to be shared across different host apps (Android, iOS, desktop) without modification.

### Settings Pattern

User settings are defined in `HkRouterPlannerEntry.userSettings()` and rendered dynamically in the host app:
- Settings use `UserSettingField` with key, label, input type, and default value
- Host app persists settings in SharedPreferences
- Business logic accesses via `ctx.settings[key]`
- AI API settings use helper `AIApiSettings.fields()` for standard OpenAI-compatible configuration

### Coroutines and Async

- Host app uses `CoroutineScope(SupervisorJob() + Dispatchers.Main)` for lifecycle-aware async operations
- Commands are `suspend` functions that can make network calls, use device sensors, etc.
- Display updates are non-blocking via `ctx.client.display()`

### Placeholder Replacement

Build files contain placeholders replaced by `xg-glass init`:
- `__XG_SDK_PATH__`: Absolute path to xg-glass SDK
- `__XG_ENTRY_CLASS__`: Fully qualified class name of entry point

Do not manually edit these placeholders; use `xg-glass init` to regenerate.

### Device-Specific Features

**Meta AI Glasses:**
- Requires GitHub token with `read:packages` scope (GitHub Packages dependency)
- Automatically sets `minSdk = 29` when token is available

**Rokid (CXR-M v1.0.4):**
- Requires SN authorization file (`.lc`) and developer `clientSecret`
- Two configuration methods:
  1. **Runtime UI** (recommended): Select device, pick `.lc` file, enter secret
  2. **Build-time config**: Place `.lc` in `app/src/main/res/raw/` and set credentials in `local.properties`
- Runtime credentials take precedence over build-time config

**APK Splits:**
- Per-ABI APKs generated by default for smaller size (`arm64-v8a`, `armeabi-v7a`)
- Add `x86_64` to splits configuration if running on Android emulator

## File Locations

- Main entry point: `ug_app_logic/src/main/java/com/example/hongkongrouterplanner/logic/HkRouterPlannerEntry.kt`
- Host activity: `app/src/main/java/com/example/xgglassapp/MainActivity.kt`
- Debug helper: `scripts/debug-app.sh`
- Project config: `xg-glass.yaml`
