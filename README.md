# Whatacall — Your World, One Call Away

Production-ready native Android messaging, status updates, and WebRTC audio/video calling app built with **Kotlin**, **Jetpack Compose**, **Material 3**, **WebRTC**, and **Firebase**.

## Public Download & Install via GitHub (No Play Store Required)

This repository includes an automated GitHub Actions workflow (`.github/workflows/build-apk.yml`) that builds and publishes a public installable **Whatacall.apk** directly under **GitHub Releases**:

1. Push this project to a **Public** GitHub repository (`main` or `master` branch).
2. Open the **Actions** tab in your GitHub repository and wait ~3–4 minutes for **Build and Publish Whatacall APK** to finish.
3. Anyone can now download and install the app directly from your repository's **Releases** page:
   - `https://github.com/<YOUR_USERNAME>/<YOUR_REPO>/releases/latest/download/Whatacall.apk`
4. Open `Whatacall.apk` on Android and allow **Install unknown apps** for your browser/file manager.

> **Why Google Sign-In works in the GitHub-built APK:** The workflow decodes `debug.keystore.base64` during the build so the published APK is signed with the exact SHA-1 certificate already registered in `app/google-services.json`.

### Optional GitHub Secrets (for TURN Relay)
If you want WebRTC calls to relay across strict mobile carrier NATs/firewalls in GitHub builds, add these under **GitHub Repo → Settings → Secrets and variables → Actions**:
- `TURN_SERVER_URL`
- `TURN_USERNAME`
- `TURN_CREDENTIAL`

### Required Firebase Console Setup
Before distributing to users, enable the remaining Console-only services documented in [`BACKEND_AND_DEPLOYMENT.md`](BACKEND_AND_DEPLOYMENT.md):
- **Firebase Console → Authentication → Phone** (Enable)
- **Firebase Console → Storage** (Initialize & apply `storage.rules`)
- **Cloud Function (`notifyIncomingCall`)** for incoming call push notifications when the app is closed.
