# DriftCourse Android

Thin client for the DriftCourse server (`../server/`). Streams chat over HTTP + SSE; no on-device inference.

## Prerequisites
- Android Studio Koala or newer
- JDK 17
- A phone / emulator on the same LAN as the mini-PC server (default `http://192.168.1.7:8787`)

## Build & run
1. Open `android/` in Android Studio. Let Gradle sync.
2. Connect a device (USB debugging ON) and hit Run, or `./gradlew installDebug` from the command line.

## Pair with the server
1. On first launch you land on Settings (no token yet).
2. Paste the server URL and the token from `server/.drift-token`.
3. Tap `/health` to confirm. Then back out to the chat screen.
