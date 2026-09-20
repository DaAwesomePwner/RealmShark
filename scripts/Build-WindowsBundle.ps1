param([string] $JavaHome, [string] $GradleHome)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if (!$JavaHome) { $JavaHome = (Get-ChildItem "$projectRoot/.tools" -Directory -Filter 'jdk-17*' | Select-Object -First 1).FullName }
if (!$GradleHome) { $GradleHome = Join-Path $projectRoot '.tools/gradle-7.6.4' }
if (!(Test-Path "$JavaHome/bin/jpackage.exe")) { throw 'RealmShark packaging requires -JavaHome pointing to a Windows x64 JDK 17 with jpackage.' }
$JavaHome = (Resolve-Path -LiteralPath $JavaHome).Path
$GradleHome = (Resolve-Path -LiteralPath $GradleHome).Path
$env:JAVA_HOME = $JavaHome
$env:GRADLE_USER_HOME = Join-Path $projectRoot '.tools/gradle-home'
$env:PATH = "$JavaHome/bin;$env:PATH"

function Publish-RealmSharkDesktopFile {
    param([string] $Source, [string] $Destination)

    $digest = (Get-FileHash -LiteralPath $Source -Algorithm SHA256).Hash
    if ([System.IO.File]::Exists($Destination) -and
        (Get-FileHash -LiteralPath $Destination -Algorithm SHA256).Hash -eq $digest) { return }
    $directory = Split-Path -Parent $Destination
    [System.IO.Directory]::CreateDirectory($directory) | Out-Null
    $staged = Join-Path $directory ('.RealmShark-' + [guid]::NewGuid().ToString('N') + '.tmp')
    $backup = "$staged.previous"
    try {
        Copy-Item -LiteralPath $Source -Destination $staged
        if ((Get-FileHash -LiteralPath $staged -Algorithm SHA256).Hash -ne $digest) {
            throw "RealmShark desktop output changed while staging: $Source"
        }
        # Replace only complete published files; development launches stage their own immutable JARs.
        if ([System.IO.File]::Exists($Destination)) {
            [System.IO.File]::Replace($staged, $Destination, $backup)
        } else {
            [System.IO.File]::Move($staged, $Destination)
        }
    } finally {
        foreach ($temporary in @($staged, $backup)) {
            if ([System.IO.File]::Exists($temporary)) { [System.IO.File]::Delete($temporary) }
        }
    }
}

function Publish-RealmSharkArchive {
    param([string] $Archive, [string] $ShareDirectory)

    # Stage on the same volume so readers see complete files, never partial copies.
    $publication = [guid]::NewGuid().ToString('N')
    $latest = Join-Path $ShareDirectory 'RealmShark-Windows-x64.zip'
    $stagedZip = Join-Path $ShareDirectory ('.' + $publication + '.zip')
    $stagedHash = "$stagedZip.sha256"
    $backupZip = "$stagedZip.previous"
    $backupHash = "$stagedHash.previous"
    $lock = $null
    $zipPublished = $false
    $hashPublished = $false
    try {
        Copy-Item -LiteralPath $Archive -Destination $stagedZip
        Copy-Item -LiteralPath "$Archive.sha256" -Destination $stagedHash
        $digest = (Get-FileHash -LiteralPath $stagedZip -Algorithm SHA256).Hash
        if ([System.IO.File]::ReadAllText($stagedHash).Trim() -ne "$digest  RealmShark-Windows-x64.zip") {
            throw 'RealmShark archive changed while staging the latest download.'
        }
        # Serialize publishers. The checksum is the commit marker; each rename is atomic.
        $lock = [System.IO.File]::Open((Join-Path $ShareDirectory '.publish.lock'), 'OpenOrCreate', 'ReadWrite', 'None')
        if ([System.IO.File]::Exists($latest)) {
            [System.IO.File]::Replace($stagedZip, $latest, $backupZip)
        } else {
            [System.IO.File]::Move($stagedZip, $latest)
        }
        $zipPublished = $true
        if ([System.IO.File]::Exists("$latest.sha256")) {
            [System.IO.File]::Replace($stagedHash, "$latest.sha256", $backupHash)
        } else {
            [System.IO.File]::Move($stagedHash, "$latest.sha256")
        }
        $hashPublished = $true
    } catch {
        # A failed second rename must not leave the previous checksum with a new ZIP.
        if ($zipPublished -and !$hashPublished) {
            if ([System.IO.File]::Exists($backupZip)) {
                [System.IO.File]::Replace($backupZip, $latest, $stagedZip)
            } else {
                [System.IO.File]::Delete($latest)
            }
            $zipPublished = $false
        }
        throw
    } finally {
        if ($null -ne $lock) { $lock.Dispose() }
        $temporaryFiles = @($stagedZip, $stagedHash)
        if (!$zipPublished -or $hashPublished) { $temporaryFiles += @($backupZip, $backupHash) }
        foreach ($temporary in $temporaryFiles) {
            if ([System.IO.File]::Exists($temporary)) { [System.IO.File]::Delete($temporary) }
        }
    }
    Write-Output "Latest RealmShark download: $latest"
    Write-Output "RealmShark SHA-256: $latest.sha256"
}

Push-Location $projectRoot
try {
    # Isolate both task outputs and Gradle's output-cleanup history from legacy build directories.
    $share = Join-Path $projectRoot 'build/share'
    $release = Join-Path $share ((Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N'))
    $buildDirectory = Join-Path $release 'build'
    $projectCache = Join-Path $release '.gradle'
    New-Item -ItemType Directory -Path $release | Out-Null
    $testReport = Join-Path $buildDirectory 'reports/tests/test/index.html'
    Write-Output "RealmShark isolated build: $buildDirectory"
    Write-Output "RealmShark test report: $testReport"
    & "$GradleHome/bin/gradle.bat" "-PrealmSharkBuildDir=$buildDirectory" '-PrealmSharkVersion=v1.2.3' --project-cache-dir $projectCache test generateBranding shadowJar --no-daemon --console=plain
    if ($LASTEXITCODE) { throw "RealmShark Gradle build/tests failed. Test report (when available): $testReport" }
    $sourceJar = Join-Path $buildDirectory 'libs/RealmShark-v1.2.3.jar'
    $iconDirectory = Join-Path $buildDirectory 'generated/branding/icon'
    $icon = Join-Path $iconDirectory 'realmshark.ico'
    if (!(Test-Path -LiteralPath $icon -PathType Leaf)) { throw 'RealmShark generateBranding did not produce the Windows icon.' }
    $copyright = [regex]::Match([System.IO.File]::ReadAllText("$projectRoot/LICENSE.md"), '(?im)^Copyright[^\r\n]*').Value.Trim()
    if (!$copyright) { throw 'RealmShark LICENSE.md must contain the copyright notice used for EXE metadata.' }
    # Every build gets a fresh directory: never replace a running release JAR.
    $inputDir = Join-Path $release 'input'
    $classes = Join-Path $release 'launcher-classes'
    New-Item -ItemType Directory -Path $inputDir, $classes | Out-Null
    Copy-Item -LiteralPath $sourceJar -Destination $inputDir
    Copy-Item -LiteralPath $icon -Destination (Join-Path $inputDir 'RealmShark.ico')
    & "$JavaHome/bin/javac.exe" --release 17 -d $classes "$PSScriptRoot/packaging/PortableLauncher.java"
    if ($LASTEXITCODE) { throw 'RealmShark launcher compilation failed.' }
    & "$JavaHome/bin/jar.exe" --create --file "$inputDir/portable-launcher.jar" -C $classes .
    if ($LASTEXITCODE) { throw 'RealmShark launcher JAR creation failed.' }
    # JDK 17 options: https://docs.oracle.com/en/java/javase/17/docs/specs/man/jpackage.html
    # Keep native java/javaw commands: the portable launcher starts a child JVM.
    $packageArguments = @(
        '--type', 'app-image', '--name', 'RealmShark', '--app-version', '1.2.3',
        '--description', 'RealmShark desktop companion for Realm of the Mad God',
        '--vendor', 'RealmShark contributors', '--copyright', $copyright,
        '--icon', $icon,
        '--input', $inputDir, '--main-jar', 'portable-launcher.jar', '--main-class', 'PortableLauncher',
        '--dest', $release,
        '--add-modules', 'java.se,jdk.unsupported,jdk.crypto.ec,jdk.crypto.mscapi,jdk.localedata,jdk.charsets',
        '--jlink-options', '--strip-debug --no-man-pages --no-header-files'
    )
    & "$JavaHome/bin/jpackage.exe" @packageArguments
    if ($LASTEXITCODE) { throw 'RealmShark jpackage failed.' }
    $bundle = Join-Path $release 'RealmShark'
    foreach ($required in @('RealmShark.exe', 'runtime/bin/javaw.exe', 'runtime/bin/java.exe', 'app/portable-launcher.jar', 'app/RealmShark-v1.2.3.jar', 'app/RealmShark.ico')) {
        if (!(Test-Path -LiteralPath (Join-Path $bundle $required))) { throw "Incomplete RealmShark bundle: $required" }
    }
    Copy-Item "$projectRoot/LICENSE.md" $bundle
    Copy-Item "$projectRoot/rotmg_loot_drops_updated.csv" $bundle
    Copy-Item "$projectRoot/docs/LOOT-CATALOG-LICENSE.txt" $bundle
    Copy-Item "$projectRoot/docs/UNITYPY-LICENSE.txt" $bundle
    Copy-Item "$projectRoot/docs/BRIDGE.md" "$bundle/BRIDGE.md"
    Copy-Item "$projectRoot/docs/WINDOWS-BUNDLE.md" "$bundle/READ-ME-FIRST.md"
    Set-Content -LiteralPath "$bundle/Preview-RealmShark.cmd" -Encoding Ascii -Value "@echo off`r`ncd /d `"%~dp0`"`r`nstart `"`" `"%~dp0RealmShark.exe`" --preview"
    # Only staged JARs, runtime, launcher, public catalog and documentation enter the release.
    $zip = Join-Path $release 'RealmShark-Windows-x64.zip'
    # Windows PowerShell's older framework target otherwise permits backslash ZIP paths.
    [System.AppContext]::SetSwitch('Switch.System.IO.Compression.ZipFile.UseBackslash', $false)
    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [System.IO.Compression.ZipFile]::CreateFromDirectory($bundle, $zip, [System.IO.Compression.CompressionLevel]::Optimal, $true)
    (Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash + '  RealmShark-Windows-x64.zip' | Set-Content "$zip.sha256" -Encoding Ascii
    & (Join-Path $PSScriptRoot 'Test-WindowsBundle.ps1') -BundlePath $bundle -ZipPath $zip -BuildDirectory $buildDirectory -JavaHome $JavaHome -ExpectedJarVersion 'v1.2.3'
    # Publish only desktop launch inputs after checking the package against this isolated build.
    $pngs = @(Get-ChildItem -LiteralPath $iconDirectory -File | Where-Object { $_.Name -match '^realmshark-\d+\.png$' })
    if (!$pngs.Count) { throw 'RealmShark generateBranding did not produce the desktop PNG icons.' }
    $desktopIconDirectory = Join-Path $projectRoot 'build/generated/branding/icon'
    foreach ($png in $pngs) {
        Publish-RealmSharkDesktopFile -Source $png.FullName -Destination (Join-Path $desktopIconDirectory $png.Name)
    }
    Publish-RealmSharkDesktopFile -Source $icon -Destination (Join-Path $desktopIconDirectory 'realmshark.ico')
    Publish-RealmSharkDesktopFile -Source $sourceJar -Destination (Join-Path $projectRoot 'build/libs/RealmShark-v1.2.3.jar')
    Publish-RealmSharkArchive -Archive $zip -ShareDirectory $share
    Write-Output "Archived RealmShark bundle: $zip"
    Write-Output "RealmShark package verification: powershell.exe -NoProfile -ExecutionPolicy Bypass -File `"$PSScriptRoot/Test-WindowsBundle.ps1`""
} finally { Pop-Location }
