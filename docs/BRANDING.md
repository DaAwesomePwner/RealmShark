# RealmShark branding and desktop identity

The public product name is **RealmShark**. The main window, About dialog, CLI help, extraction prompts, executable metadata and launchers use the product version generated from `build.gradle`.

## Fin artwork

`src/main/java/realmshark/branding/FinIcon.java` is the canonical vector artwork. It draws the existing fin/waterline mark used by the workspace and the violet-on-dark application badge.

The Gradle `generateBranding` task renders 16, 20, 24, 32, 48, 64, 128 and 256-pixel PNGs and a multi-image ICO. Generated resources are under `build/generated/branding/icon/`; application resources use `/icon/realmshark-<size>.png`. The former tomato artwork is removed.

`AppIdentity` supplies the same image family to the main window, auxiliary windows and About dialog. The JAR's public entry point is `realmshark.RealmShark`.

## Windows

- `jpackage --icon` embeds the fin images into `RealmShark.exe`.
- The portable bundle also includes `app/RealmShark.ico` for taskbar relaunch metadata.
- The Windows process and windows use the stable AppUserModelID `RealmShark.Desktop`.
- Both launchers supply an absolute relaunch target and icon path to the UI process. The portable launcher targets `RealmShark.exe`; the source launcher targets `Launch-RealmShark.cmd`.
- Window Shell properties are cleared on hide/dispose and before application exit, with a native-only shutdown fallback.

The native integration uses the existing JNA dependency. Other operating systems use the common Swing icons without loading Windows APIs. Generated-image and native-property tests cover resource consistency, identity, command quoting and cleanup. `scripts/Test-WindowsBundle.ps1` verifies the actual embedded EXE images and package metadata.

## Compatibility and attribution

Product branding is separate from compatibility identifiers. Historical internal Java class names remain readable in old serialized DPS recordings; existing preference locations and bridge wire identifiers remain compatible. The bridge's exact outgoing JSON continues to display its actual protocol fields. These are not alternative application names.

Upstream release notices distinguish upstream changes from this custom RealmShark build. Original license terms and contributor credits are preserved; historical import details remain in `UI-REDESIGN.md`.

## Verified build — 2026-09-19

- All **237 tests passed**, with no failures or ignored tests, including generated-image consistency, public titles/About/help, native Shell properties and real JVM-exit cleanup.
- The Windows EXE's metadata and all **eight embedded icon sizes** matched the canonical fin ICO. Archive checks verified its SHA-256, current application JAR, entry point, icons, public-only payload and licenses.
- An extracted package was launched with `--preview` in an isolated temporary directory. The live title was `RealmShark v1.2.3 | Preview`; Windows reported `RealmShark.Desktop` and relaunch properties pointing to that package's `RealmShark.exe` and `app/RealmShark.ico`.
- Both actual window icons (32px and 16px) matched the packaged PNGs with zero differing visible pixels. Closing the preview exited its wrapper and UI processes successfully. Physical taskbar pin/unpin interaction was not exercised.

The latest verified package is `build/share/RealmShark-Windows-x64.zip`, with its `.sha256` sidecar. Packaging builds in a fresh timestamped release directory to isolate compiled outputs, verifies the archive, then publishes the desktop JAR/icons and latest ZIP. Each release retains its test report under `build/share/<release>/build/reports/tests/test/index.html`.
