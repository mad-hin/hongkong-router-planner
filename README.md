# Hong Kong Router Planner (xg-glass)

This project is an xg-glass Android app for voice-first Hong Kong transit helpers.

Main app entry:

- `ug_app_logic/src/main/java/com/example/hongkongrouterplanner/logic/HkRouterPlannerEntry.kt`

Implemented commands:

1. Weather Report
2. Plan My Route
3. Live Bus ETA

## App behavior

- AI settings (API key/base URL/model) are configured in the host app Settings panel.
- Current district is auto-detected from Android GPS (no manual district input required).
- Point-to-point route search in `Plan My Route` uses a DFS nearby-stop method:
  - max 500m walk radius for start/end-to-stop
  - max one transfer (search depth up to 2)
  - route DB source: `https://data.hkbus.app/routeFareList.min.json`

## How to build / install / run

From your project root (the directory that contains `xg-glass.yaml`):

- `xg-glass build`
- `xg-glass install`
- `xg-glass run`

## Quick debug script

A helper script is included:

- `scripts/debug-app.sh`

Use it to run common debug flows:

```bash
chmod +x scripts/debug-app.sh

# Build + install + run + tail app logcat
scripts/debug-app.sh

# Only logs
scripts/debug-app.sh logs

# Crash-focused logs
scripts/debug-app.sh crash

# Uninstall/reinstall + run
scripts/debug-app.sh clean-install
```

If `adb` is not in your PATH, the script already uses:

- `$HOME/Android/Sdk/platform-tools/adb`

By default it starts AVD `Medium_Phone_API_36.1` if no device is connected.

## Notes

The `settings.gradle.kts` / `app/build.gradle.kts` / `AndroidManifest.xml` in this template contain placeholders:

- `__XG_SDK_PATH__`
- `__XG_ENTRY_CLASS__`

These will be replaced with actual values by `xg-glass init`.

### Meta AI Glasses note

If you want to enable **Meta AI glasses** support in a generated app, configure the Meta DAT GitHub Packages token before building:

```properties
# ~/.gradle/gradle.properties
github_token=ghp_xxxxxxxxxxxxx
```

or:

```bash
export GITHUB_TOKEN=ghp_xxxxxxxxxxxxx
```

Notes:

- The token needs at least GitHub `read:packages` scope.
- When Meta support is enabled, the template host app automatically uses `minSdk 29`.

### Rokid note (CXR-M v1.0.4)

If you are connecting to **Rokid** glasses, CXR-M **v1.0.4** requires an SN authorization file (`.lc`) and your developer `clientSecret`.

#### Option A – In-app UI (recommended for end users)

1. Select **ROKID** from the device spinner.
2. Tap **Select SN License (.lc)** and pick your `.lc` file from the device.
3. Enter your **Client Secret** in the text field.
4. Tap **Connect**.

Credentials are persisted locally so you only need to do this once.

#### Option B – Build-time config (for developers)

- Put the SN authorization file into: `app/src/main/res/raw/`
  - Example: `app/src/main/res/raw/sn_0a9813....lc` (resource name is `sn_0a9813....`, **without** extension)
- Add the following to your project root `local.properties` (**do not commit it**):

```properties
rokid.clientSecret=xxxxxxxxxxxxxxxx
rokid.snRawName=sn_0a9813....
```

Alternatively, you can set environment variables: `ROKID_CLIENT_SECRET` and `ROKID_SN_RAW_NAME`.

> **Priority**: runtime credentials (Option A) take precedence over build-time config (Option B).
