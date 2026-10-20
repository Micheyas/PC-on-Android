# PC on Android (working title)

Run Windows PC apps and games on Android — BlueStacks in reverse.
Built for the phones people actually have: budget MediaTek/Mali devices,
for people who have no PC.

> Working title only — rename freely. Full plan: ask mikiyas / see the plan doc.

## Where this is

**Starter skeleton (v0.2).** A Kotlin app shell that already does two real things:

1. **Device profiler** (`DeviceProfiler.kt`) — detects the phone's chip, GPU
   family (Adreno vs Mali) and RAM, and picks the recommended graphics driver.
   This grows into the auto-settings brain: the user never touches settings.
2. **File picker** — pick a Windows `.exe` from the phone's storage.
   Running it (Wine + Box64 engine) is the next step.

## Build it

**In the cloud (no PC needed):** push to `main` — GitHub Actions builds the APK.
Download it from the repo's **Releases → Latest build**, or from the Actions run artifact.

**On a PC:** `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`

**On the phone:** open the project in CodeAssist and build on-device.

## Roadmap

- [x] App shell + device profiler + file picker
- [ ] Bundle Wine + Box64 runtime, launch a first light .exe
- [ ] Auto-settings per chip (driver / resolution / presets)
- [ ] Library home screen + per-app profiles
- [ ] First 10 tuned target apps
