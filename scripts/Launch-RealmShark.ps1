param([Parameter(ValueFromRemainingArguments = $true)][string[]] $ApplicationArguments)

$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'RuntimeJar.ps1')

try {
    $sourceJar = Join-Path $projectDirectory 'build\libs\RealmShark-v1.2.3.jar'
    $icon = Join-Path $projectDirectory 'build\generated\branding\icon\realmshark.ico'
    $launcher = Join-Path $projectDirectory 'Launch-RealmShark.cmd'
    if (!(Test-Path -LiteralPath $sourceJar) -or !(Test-Path -LiteralPath $icon)) {
        throw 'Build RealmShark first with gradlew.bat shadowJar using JDK 17 (including generateBranding).'
    }
    $runtimeJar = New-RealmSharkRuntimeJar -SourceJar $sourceJar -RuntimeDirectory (Join-Path $projectDirectory '.runtime')
    $javaCommand = 'javaw.exe'
    foreach ($jdk in Get-ChildItem -LiteralPath (Join-Path $projectDirectory '.tools') -Directory -Filter 'jdk-*' -ErrorAction SilentlyContinue) {
        $candidate = Join-Path $jdk.FullName 'bin\javaw.exe'
        if (Test-Path -LiteralPath $candidate) { $javaCommand = $candidate }
    }
    # javaw has no console, so ask its sibling java.exe for the runtime version before a hidden launch.
    $probe = if ($javaCommand -eq 'javaw.exe') { 'java.exe' } else { $javaCommand -replace 'javaw\.exe$', 'java.exe' }
    # java -XshowSettings writes to stderr; Windows PowerShell 5.1 turns redirected native stderr into a
    # terminating error under 'Stop' (see Test-BuildMaintenance.ps1), so relax it for this call only.
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $specification = & $probe -XshowSettings:properties -version 2>&1 |
            ForEach-Object { if ("$_" -match 'java\.specification\.version = (\S+)') { $Matches[1] } } | Select-Object -First 1
    } finally { $ErrorActionPreference = $previousPreference }
    $feature = if ($specification) { [int]($specification -replace '^1\.', '') } else { 0 }
    if ($feature -lt 17) {
        throw "RealmShark needs Java 17 or newer, but '$probe' reports '$specification'. Install JDK 17 or place it in .tools\jdk-17*."
    }

    # Quote argv for Windows' process command-line parser, including spaces and trailing slashes.
    $javaArguments = @("-Drealmshark.launcher=$launcher", "-Drealmshark.icon=$icon", '-jar', $runtimeJar) + @($ApplicationArguments)
    $quotedArguments = foreach ($argument in $javaArguments) {
        if ($null -ne $argument) {
            '"' + [regex]::Replace([regex]::Replace($argument, '(\\*)"', '$1$1\"'), '(\\+)$', '$1$1') + '"'
        }
    }
    Start-Process -FilePath $javaCommand -ArgumentList ($quotedArguments -join ' ') -WorkingDirectory $projectDirectory -WindowStyle Hidden
} catch {
    Write-Error -Message ('Unable to launch RealmShark: ' + $_.Exception.Message) -ErrorAction Continue
    exit 1
}
