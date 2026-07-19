# AGP Incompatibility: Options and Implementation Plan

The project is currently using Android Gradle Plugin (AGP) `9.4.0-alpha04`, but your current Android Studio installation only supports up to `9.3.0`.

## Options

### Option 1: Downgrade AGP in the project (Recommended)
This is the most straightforward way to get the project working in your current IDE. It involves changing the AGP version in the project configuration.
* **Pros:** IDE features like Sync, Code Navigation, and Layout Editor will work immediately.
* **Cons:** You might lose access to experimental features only available in the `9.4.0` alpha.

### Option 2: Upgrade Android Studio
Install the **Canary** or **Dev** version of Android Studio that supports AGP `9.4.0`.
* **Pros:** Keep using the latest AGP and its new features.
* **Cons:** Requires downloading and installing a separate, potentially less stable IDE version.

### Option 3: Continue using CLI (Not Recommended)
Build the app only via the command line (e.g., `./gradlew assembleDebug`).
* **Pros:** No changes needed to the project.
* **Cons:** You lose all IDE-assisted development features (Sync, Error Highlighting, Composable Previews, etc.).

---

## Implementation Plan (for Option 1)

If you choose to downgrade, here is the plan:

### Proposed Changes

#### [MODIFY] [libs.versions.toml](file:///C:/Users/Jana/StudioProjects/Modern-Apps/gradle/libs.versions.toml)
- Change `agp = "9.4.0-alpha04"` to `agp = "9.3.0"`.

### Verification Plan

#### Automated Tests
- Run `gradle_sync` to ensure the project is recognized by the IDE.
- Run `:weather:assembleDebug` (as requested earlier) to ensure the build still passes.

#### Manual Verification
- Verify the "Incompatible AGP Version" error is gone from the sync logs.
