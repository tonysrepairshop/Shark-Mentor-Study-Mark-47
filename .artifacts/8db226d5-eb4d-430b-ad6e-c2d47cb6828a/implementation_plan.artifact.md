# Implementation Plan - Fix [AppDoctor] null InputStream Error

The error `[AppDoctor] null InputStream [CONTEXT service_id=254 ]` is an internal Google Play Services (GMS) issue. It typically occurs when the GMS Feedback or Diagnostic service (service_id 254) attempts to read system logs from the `DropBoxManager` but encounters a null stream, often due to permission restrictions or missing configuration in the `google-services.json` for that specific internal service.

While often non-fatal, it can clutter logs and sometimes indicate an incompatibility between the GMS/Firebase client libraries and the version of Google Play Services installed on the device.

## Proposed Changes

### [Component Name] Dependency Updates

We will update the Firebase Bill of Materials (BoM) to the latest stable version and explicitly add `play-services-base` to ensure the core GMS components are at a consistent, stable version. We will also align the `compileSdk` and `targetSdk` to stable versions if they are unnecessarily high.

#### [MODIFY] [libs.versions.toml](file:///C:/Users/hp/AndroidStudioProjects/SharkMentor/gradle/libs.versions.toml)
- Update `firebaseBom` to `33.10.0` (or latest stable available in the environment).
- Add `playServicesBase` library definition.

#### [MODIFY] [app/build.gradle.kts](file:///C:/Users/hp/AndroidStudioProjects/SharkMentor/app/build.gradle.kts)
- Update `compileSdk` and `targetSdk` to `35` (Android 15), as `37` is currently a preview/future SDK which may cause instability in GMS components.
- Add explicit dependency for `play-services-base`.

## Verification Plan

### Automated Tests
- Build the project to ensure dependency resolution is successful.
- Run the application and check Logcat to verify that the `[AppDoctor]` error no longer appearing (or is reduced).

### Manual Verification
- Deploy to a device/emulator with Google Play Services.
- Verify that Firestore sync and Gemini features still work correctly after the SDK version change.
