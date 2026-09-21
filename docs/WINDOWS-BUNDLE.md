# RealmShark for Windows x64

1. Install Npcap from https://npcap.com/#download. Enable **WinPcap API-compatible Mode** for this application's `wpcap` loader. Npcap installs a system driver and requires administrator permission. If you restrict Npcap to administrators, RealmShark also needs to run as administrator to capture.
2. Extract the **entire ZIP** to a writable folder, such as a folder under Documents. Do not run inside the ZIP or place it in Program Files.
3. Double-click **RealmShark.exe**. Java is included; no separate Java installation is needed. Keep the `app` and `runtime` folders next to the EXE.
4. Have RotMG Exalt installed. On first use, RealmShark extracts the data it needs from your own game installation. If it cannot find `resources.assets`, select your installation when prompted (or launch with `--path "full path to resources.assets"`).
5. Start capture using the application controls, then reconnect to the game so capture sees a fresh connection.

**Preview-RealmShark.cmd** opens the UI without capture, API requests or game-asset extraction.

The application window, About screen, executable and taskbar use the **RealmShark fin logo**. Window and package metadata identify the product as **RealmShark v1.2.3**. Create shortcuts to `RealmShark.exe`; the launcher supplies the same stable desktop identity and icon to its UI process. If an older pinned shortcut retains a previous icon, unpin that shortcut and pin the new `RealmShark.exe`.

This is a portable folder with an EXE launcher, not a single-file executable or an installer. Settings, extracted assets, optional text logs and manual exports use the application folder. Automatic app-session history is stored in **`%LOCALAPPDATA%\RealmShark\history`**, so newer builds on the same Windows account find it automatically. Each supported module starts on Current Session and offers past-session/All Sessions views. **Import old folder…** can bring saved runs and `.fame` sessions from an older installation into the shared archive. Share the original ZIP rather than personal data folders. Normal startup retains the application's existing API requests and behavior.

The build is unsigned; Windows may show an unknown-publisher/reputation warning. Only use a copy from someone you trust. Npcap is deliberately not bundled: its free edition does not permit redistribution (https://npcap.com/oem/redist).

For updates, close RealmShark and extract the new release into a new folder. Do not overwrite `app` or `runtime` while running. A running JVM loads classes lazily and needs its original JAR throughout the session.

## Rebuilding this release

From the source checkout, run `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Build-WindowsBundle.ps1`. The script uses the project-local JDK 17 and Gradle 7.6.4; other locations can be supplied with `-JavaHome` and `-GradleHome`. It runs tests, builds the current checkout (including uncommitted edits), generates the fin icons, and creates a fresh ZIP plus SHA-256 file under `build/share`.

Gradle compilation, tests and Java execution use a Java 17 toolchain. Main application sources target Java 8 APIs/bytecode with `--release 8`; tests, build tools and the portable launcher use Java 17. The bundle includes a Java 17 runtime. Product-version source is generated under the isolated build directory, never under `src`; upstream and asset-cache versions remain separate. Packaging explicitly builds the default product version `v1.2.3`; the Gradle version override is reserved for isolated build-contract checks. Nondefault versions require a canonical output path in a dedicated `build` subdirectory or an external build directory; normal-build aliases, project/source locations and their ancestors, and filesystem roots are rejected. Default-version build-directory behavior is unchanged.

Each release is retained under its own timestamped directory. After package verification succeeds, the script also publishes the latest download as `build/share/RealmShark-Windows-x64.zip` and `RealmShark-Windows-x64.zip.sha256`.

Run `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Test-WindowsBundle.ps1 -JavaHome "$env:JAVA_HOME" -ExpectedJarVersion v1.2.3` with JDK 17 selected to verify the latest archive's checksum, bundled files, RealmShark entry point, generated/inlined product version, Java 8 application class headers, EXE product metadata and embedded fin images. For a retained build, supply its matching `-ZipPath`, `-BundlePath` and `-BuildDirectory`. The headless JAR probe uses the development JDK; it does not start the application or bundled runtime. The same verification runs automatically before publication.

For build-only maintenance checks, first populate the Gradle wrapper/dependency cache with `gradlew.bat test shadowJar`, then run `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Test-BuildMaintenance.ps1 -JavaHome "$env:JAVA_HOME"`. This uses offline, isolated output/cache directories and checks source-tree cleanliness, version-input invalidation, up-to-date generation and fresh JAR contents. It retains evidence under `build/build-maintenance-<id>` and does not publish a Windows package.

Packaging uses OpenJDK's jpackage app-image format: https://docs.oracle.com/en/java/javase/17/docs/specs/man/jpackage.html. Runtime legal notices are under `runtime/legal`; application license is in `LICENSE.md`, and dependency notices bundled in the application JAR remain included. The UnityPy MIT notice is copied from `docs/UNITYPY-LICENSE.txt` into the bundle root and the JAR's `META-INF/licenses/UNITYPY-LICENSE.txt`; package verification checks both against the source notice.
