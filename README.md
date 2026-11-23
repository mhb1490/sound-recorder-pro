# Sound Recorder Pro (v1.1.0)

Android Kotlin / Compose, Android 10+ (minSdk 29, targetSdk 35). Package: `ir.soundrecorder.pro`.

## Recording and output
- Microphone-only (default): AAC in M4A, no screen-capture permission required.
- Allowed playback only: 48 kHz stereo 16-bit PCM WAV, with Android MediaProjection consent.
- Both: two separate recordings; simultaneous capture is device/policy dependent.
- Foreground service with visible notification and Stop. Starts only after user interaction and requested permissions; no automatic call capture.
- Five-minute segments. Active files have `.partial` extensions and are not listed. Completed files are finalized before publication to `Music/SoundRecorderPro/` through MediaStore. If publication fails, a finalized private copy is kept and an error is shown. The app can export that copy later. Old private M4A files are also listed and can be exported; invalid old files may not play.
- The internal WAV stream consumes about 57.6 MB per five-minute segment (about 691 MB/hour); microphone M4A is much smaller.
- File list refreshes on service status/segment changes and on request. Playback, Android sharing, renaming, deletion, and export of private copies are supported.

## Build
GitHub Actions: keep `.github/workflows/android.yml`, use the existing JDK 17 / Gradle 8.9 / Android SDK 35 setup, run `assembleDebug`, then retrieve the APK from Artifacts. Alternatively open the project in Android Studio with those versions. Gradle wrapper is not included. The app uses the existing Kotlin 2.0.21 / Compose plugin and AGP 8.7.3.

## Important limitations
Android restricts direct cellular/VoIP call audio to privileged/system apps and restricts playback capture to allowed media/game/unknown usages. A microphone recording can be silent during a call if Android gives the call priority. This app does not bypass those policies, and does not promise call recording. Record only with the knowledge and consent of the people involved, according to applicable law. Protected sources may yield silence. Foreground service does not guarantee recording survives force-stop, reboot, permission revocation or OEM battery restrictions. Private files may disappear on uninstall; save/export them first. There is no upload or network transfer in the app itself.

## Device acceptance checks (not run in the editing environment)
1. Install the APK over the previous version only if the signing certificate matches; otherwise back up old files first.
2. Choose microphone, grant audio/notification permissions, record speech for 10 seconds, stop, wait for the saved status. Verify the M4A plays both in the app and in the file manager under Music/SoundRecorderPro.
3. Try playback, sharing, rename, delete and export of a legacy private recording. Check the files after relaunching the app.
4. Choose internal audio, approve the Android capture prompt, play non-protected audio in a capture-permitting app. Stop and verify WAV playback. Test permission denial and revocation. A silent file can indicate source restrictions.
5. Try both streams, screen off, notification Stop, and recording longer than five minutes; check that each completed segment appears. Test a short recording, no media playing, and low storage. Verify errors are displayed rather than a misleading running state.
