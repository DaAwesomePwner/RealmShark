param([string] $JavaHome = $env:JAVA_HOME)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if (!$JavaHome) { throw 'Specify -JavaHome or JAVA_HOME pointing to JDK 17.' }
$JavaHome = (Resolve-Path -LiteralPath $JavaHome).Path
$java = Join-Path $JavaHome 'bin/java.exe'
if (!(Test-Path -LiteralPath $java) -or !(Test-Path -LiteralPath (Join-Path $JavaHome 'bin/javac.exe'))) {
    throw 'Build-contract verification requires a JDK 17 installation.'
}
$sourceRoot = Join-Path $projectRoot 'src'
$workspace = Join-Path $projectRoot ('build/build-maintenance-' + [guid]::NewGuid().ToString('N'))
$logs = Join-Path $workspace 'logs'
[System.IO.Directory]::CreateDirectory($logs) | Out-Null
$primary = Join-Path $workspace 'primary'
$primaryCache = Join-Path $workspace 'primary-cache'
$alternate = Join-Path $workspace 'alternate'
$alternateCache = Join-Path $workspace 'alternate-cache'
$probe = Join-Path $PSScriptRoot 'validation/BuildContractProbe.java'
$notice = Join-Path $projectRoot 'docs/UNITYPY-LICENSE.txt'
$utf8 = [System.Text.UTF8Encoding]::new($false)

function Get-TreeSnapshot {
    param([string] $Root, [string[]] $Directories = @('.'))
    $files = @(foreach ($directory in $Directories) {
        $path = Join-Path $Root $directory
        if (Test-Path -LiteralPath $path) { Get-ChildItem -LiteralPath $path -Recurse -File -Force }
    })
    ConvertTo-Json -Depth 3 -InputObject @($files | Sort-Object FullName | ForEach-Object {
        [pscustomobject]@{
            Path = $_.FullName.Substring($Root.Length + 1).Replace('\', '/')
            Length = $_.Length
            SHA256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash
            LastWriteTimeUtcTicks = $_.LastWriteTimeUtc.Ticks
        }
    })
}

function Get-GeneratedFingerprint {
    param([string] $BuildDirectory)
    $path = Join-Path $BuildDirectory 'generated/sources/version/main/realmshark/version/Version.java'
    $file = Get-Item -LiteralPath $path
    '{0}|{1}|{2}' -f $file.Length, (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash, $file.LastWriteTimeUtc.Ticks
}

function Invoke-ContractBuild {
    param([string] $Label, [string] $BuildDirectory, [string] $Cache, [string] $Task,
          [string] $ExpectedOutcome, [string] $VersionOverride, [switch] $ExpectVersionChange,
          [switch] $ExpectRejectedOutput)
    $arguments = @('--project-dir', $projectRoot, '--project-cache-dir', $Cache,
        "-PrealmSharkBuildDir=$BuildDirectory", '-Porg.gradle.java.installations.auto-download=false',
        '--offline', '--no-daemon', '--no-build-cache', '--no-configuration-cache', '--console=plain', '--info', $Task)
    if ($VersionOverride) { $arguments += "-PrealmSharkVersion=$VersionOverride" }
    $log = Join-Path $logs "$Label.log"
    # Capture native stderr too, allowing the exit code to report compilation failures on PowerShell 5.1.
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $output = @(& (Join-Path $projectRoot 'gradlew.bat') @arguments 2>&1)
        $exitCode = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previousPreference }
    $text = ($output | ForEach-Object { $_.ToString() }) -join "`n"
    [System.IO.File]::WriteAllText($log, $text, $utf8)
    if ($ExpectRejectedOutput) {
        if ($exitCode -eq 0 -or
                !$text.Contains('A build-contract version override requires an isolated realmSharkBuildDir.') -or
                $text -match '(?m)^> Task :') {
            throw "Expected configuration-time output isolation rejection during $Label. See $log"
        }
        Write-Output "PASS: $Label rejected before task execution ($log)."
        return
    }
    if ($exitCode -ne 0) { throw "Gradle failed during $Label (exit $exitCode). Inspect $log; main --release 8 failures require review before changing production code." }
    $outcomes = [regex]::Matches($text, '(?m)^> Task :generateSources(?<outcome> UP-TO-DATE| FROM-CACHE| SKIPPED| NO-SOURCE| FAILED)?\r?$')
    if ($outcomes.Count -ne 1 -or $outcomes[0].Groups['outcome'].Value.Trim() -ne $ExpectedOutcome) {
        throw "Unexpected generateSources outcome during $Label; expected '$ExpectedOutcome' (empty means executed). See $log"
    }
    if ($ExpectVersionChange -and !$text.Contains("Value of input property 'productVersion' has changed")) {
        throw "Missing version-input invalidation evidence during $Label. See $log"
    }
    Write-Output "PASS: $Label generateSources outcome verified ($log)."
}

function Test-BuiltJar {
    param([string] $BuildDirectory, [string] $Version)
    $jar = Join-Path $BuildDirectory "libs/RealmShark-$Version.jar"
    & $java '-Djava.awt.headless=true' '--source' '17' $probe $jar $Version 'v1.9.2' 'v1.9.1' $notice
    if ($LASTEXITCODE -ne 0) { throw "Built JAR contract failed: $jar" }
}

$before = Get-TreeSnapshot -Root $sourceRoot
[System.IO.File]::WriteAllText((Join-Path $workspace 'src-before.json'), $before, $utf8)
$previousJavaHome = $env:JAVA_HOME
Write-Output "RealmShark build-contract evidence: $workspace"
try {
    $env:JAVA_HOME = $JavaHome
    if (Test-Path -LiteralPath (Join-Path $sourceRoot 'main/java/realmshark/version/Version.java')) {
        throw 'Product Version.java must be generated only under the selected build directory.'
    }
    $changed = 'v1.2.3-build-contract'
    $normalBuild = (Resolve-Path -LiteralPath (Join-Path $projectRoot 'build')).ProviderPath
    $protectedDirectories = @('generated', 'classes', 'resources', 'libs')
    $normalBefore = Get-TreeSnapshot -Root $normalBuild -Directories $protectedDirectories
    [System.IO.File]::WriteAllText((Join-Path $logs '00-normal-output-before.log'), $normalBefore, $utf8)
    try {
        $rejectedOutputs = [ordered]@{
            'default-relative' = 'build'
            'default-absolute' = $normalBuild
            'default-normalized' = 'build/../build/.'
            'project-ancestor' = '..'
            'source-tree' = 'src'
        }
        foreach ($case in $rejectedOutputs.GetEnumerator()) {
            Invoke-ContractBuild -Label "00-reject-$($case.Key)" -BuildDirectory $case.Value `
                -Cache (Join-Path $workspace 'rejection-cache') -Task 'help' `
                -VersionOverride $changed -ExpectRejectedOutput
        }
    } finally {
        $normalAfter = Get-TreeSnapshot -Root $normalBuild -Directories $protectedDirectories
        [System.IO.File]::WriteAllText((Join-Path $logs '00-normal-output-after.log'), $normalAfter, $utf8)
        if ($normalBefore -cne $normalAfter) { throw "Output-rejection checks changed normal build outputs. Compare snapshots in $logs" }
    }
    Invoke-ContractBuild -Label '01-default' -BuildDirectory $primary -Cache $primaryCache -Task 'shadowJar' -ExpectedOutcome ''
    Test-BuiltJar -BuildDirectory $primary -Version 'v1.2.3'
    $first = Get-GeneratedFingerprint $primary
    Invoke-ContractBuild -Label '02-default-repeat' -BuildDirectory $primary -Cache $primaryCache -Task 'generateSources' -ExpectedOutcome 'UP-TO-DATE'
    if ($first -ne (Get-GeneratedFingerprint $primary)) { throw 'Up-to-date generation rewrote its output.' }

    Invoke-ContractBuild -Label '03-version-change' -BuildDirectory $primary -Cache $primaryCache -Task 'shadowJar' -ExpectedOutcome '' -VersionOverride $changed -ExpectVersionChange
    Test-BuiltJar -BuildDirectory $primary -Version $changed
    $second = Get-GeneratedFingerprint $primary
    Invoke-ContractBuild -Label '04-changed-repeat' -BuildDirectory $primary -Cache $primaryCache -Task 'generateSources' -ExpectedOutcome 'UP-TO-DATE' -VersionOverride $changed
    if ($second -ne (Get-GeneratedFingerprint $primary)) { throw 'Repeated override rewrote its output.' }

    Invoke-ContractBuild -Label '05-fresh-default' -BuildDirectory $alternate -Cache $alternateCache -Task 'shadowJar' -ExpectedOutcome ''
    Test-BuiltJar -BuildDirectory $alternate -Version 'v1.2.3'
} finally {
    $env:JAVA_HOME = $previousJavaHome
    $after = Get-TreeSnapshot -Root $sourceRoot
    [System.IO.File]::WriteAllText((Join-Path $workspace 'src-after.json'), $after, $utf8)
    if ($before -cne $after) { throw "Build-contract verification changed src paths, bytes or write times. Compare snapshots in $workspace" }
}
Write-Output 'PASS: output isolation, source-tree cleanliness, up-to-date generation, version-input invalidation, and fresh-build JAR contracts.'
