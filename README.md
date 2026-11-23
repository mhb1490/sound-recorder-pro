# Sound Recorder Pro (v1.0.0)

Android Kotlin project for Android 10+ (`minSdk 29`, `targetSdk 35`). Package: `ir.soundrecorder.pro`.

## Included
- Microphone capture to segmented M4A/AAC files (5-minute segments).
- Android playback capture to a separate M4A/AAC stream, subject to MediaProjection consent and Android/app capture policies.
- Foreground service with a persistent notification and a stop action.
- Compose UI and a local list of recordings.
- Files are stored under the app-specific external `Recordings/` directory (or internal app files if external app storage is unavailable).

## Build
Open this folder in Android Studio (Jellyfish or newer) and sync Gradle. Use JDK 17 and Android SDK 35, then Build > Build Bundle(s) / APK(s) > Build APK(s).

There is no Gradle wrapper binary included; Android Studio can import the project and use its installed Gradle distribution.

## Important platform behavior
- The user must explicitly grant microphone permission and approve the Android screen/audio capture prompt each time a playback-capture session starts.
- Playback capture is limited to eligible usages and apps that permit capture. It does not capture all device audio, protected content, or reliably capture cellular/VoIP calls.
- Microphone recording and playback capture are separate files because Android does not guarantee a single synchronized mixed stream.
- Android/OEM battery policies can stop long-running processes. The app uses a foreground service and a visible persistent notification, but cannot promise uninterrupted recording after force-stop, reboot, permission revocation, or manufacturer restrictions.
- This app intentionally does not hide recording. Recording should only be used with the knowledge and consent of people whose conversations may be captured, and in compliance with local law.

## Storage
Recordings remain in app-specific storage. They are not automatically uploaded or shared. Uninstalling the app may remove these files; export/backup is not included in v1.
