param([Parameter(ValueFromRemainingArguments = $true)][string[]] $ApplicationArguments)

$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'RuntimeJar.ps1')
. (Join-Path $PSScriptRoot 'JavaRuntime.ps1')

try {
    $sourceJar = Join-Path $projectDirectory 'build\libs\RealmShark-v1.2.3.jar'
    $icon = Join-Path $projectDirectory 'build\generated\branding\icon\realmshark.ico'
    $launcher = Join-Path $projectDirectory 'Launch-RealmShark.cmd'
    if (!(Test-Path -LiteralPath $sourceJar) -or !(Test-Path -LiteralPath $icon)) {
        throw 'Build RealmShark first with gradlew.bat shadowJar using JDK 17 (including generateBranding).'
    }
    $runtimeJar = New-RealmSharkRuntimeJar -SourceJar $sourceJar -RuntimeDirectory (Join-Path $projectDirectory '.runtime')
    $javaCommand = Find-RealmSharkJavaw -ToolsDirectory (Join-Path $projectDirectory '.tools')

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
