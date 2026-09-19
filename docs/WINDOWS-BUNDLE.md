# RealmShark for Windows x64

1. Install Npcap from https://npcap.com/#download. Enable **WinPcap API-compatible Mode** for this application's `wpcap` loader. Npcap installs a system driver and requires administrator permission. If you restrict Npcap to administrators, RealmShark also needs to run as administrator to capture.
2. Extract the **entire ZIP** to a writable folder, such as a folder under Documents. Do not run inside the ZIP or place it in Program Files.
3. Double-click **RealmShark.exe**. Java is included; no separate Java installation is needed. Keep the `app` and `runtime` folders next to the EXE.
4. Have RotMG Exalt installed. On first use, RealmShark extracts the data it needs from your own game installation. If it cannot find `resources.assets`, select your installation when prompted (or launch with `--path "full path to resources.assets"`).
5. Start capture using the application controls, then reconnect to the game so capture sees a fresh connection.

**Preview-RealmShark.cmd** opens the UI without capture, API requests or game-asset extraction.

The application window, About screen, executable and taskbar use the **RealmShark fin logo**. Window and package metadata identify the product as **RealmShark v1.2.3**. Create shortcuts to `RealmShark.exe`; the launcher supplies the same stable desktop identity and icon to its UI process. If an older pinned shortcut retains a previous icon, unpin that shortcut and pin the new `RealmShark.exe`.

This is a portable folder with an EXE launcher, not a single-file executable or an installer. Settings, extracted assets, saved sessions and logs are created in this folder. These may contain personal game information: share the original ZIP, not your used folder. Normal startup retains the application's existing API requests and behavior.

The build is unsigned; Windows may show an unknown-publisher/reputation warning. Only use a copy from someone you trust. Npcap is deliberately not bundled: its free edition does not permit redistribution (https://npcap.com/oem/redist).

For updates, close RealmShark and extract the new release into a new folder. Do not overwrite `app` or `runtime` while running. A running JVM loads classes lazily and needs its original JAR throughout the session.

## Rebuilding this release

From the source checkout, run `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Build-WindowsBundle.ps1`. The script uses the project-local JDK 17 and Gradle 7.6.4; other locations can be supplied with `-JavaHome` and `-GradleHome`. It runs tests, builds the current checkout (including uncommitted edits), generates the fin icons, and creates a fresh ZIP plus SHA-256 file under `build/share`.

Each release is retained under its own timestamped directory. After package verification succeeds, the script also publishes the latest download as `build/share/RealmShark-Windows-x64.zip` and `RealmShark-Windows-x64.zip.sha256`.

Run `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Test-WindowsBundle.ps1` to verify the latest archive's checksum, bundled files, RealmShark entry point, EXE product metadata and embedded fin images. The same verification runs automatically before publication.

Packaging uses OpenJDK's jpackage app-image format: https://docs.oracle.com/en/java/javase/17/docs/specs/man/jpackage.html. Runtime legal notices are under `runtime/legal`; application license is in `LICENSE.md`, and dependency notices bundled in the application JAR remain included.
