param([string] $JavaHome = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'JavaRuntime.ps1')
$fixtureRoot = Join-Path ([System.IO.Path]::GetTempPath()) ('realmshark-java-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $fixtureRoot | Out-Null
try {
    $versions = @{}
    function Add-Candidate([string] $name, [string] $version, [long] $build = 0, [bool] $stable = $true) {
        $bin = Join-Path $fixtureRoot "$name\bin"
        New-Item -ItemType Directory -Path $bin | Out-Null
        New-Item -ItemType File -Path (Join-Path $bin 'java.exe'), (Join-Path $bin 'javaw.exe') | Out-Null
        $versions[(Join-Path $bin 'java.exe')] = if ($version) { [pscustomobject]@{Version=[version]$version; Build=$build; Stable=$stable} } else { $null }
        return (Join-Path $bin 'javaw.exe')
    }
    $seen = New-Object 'System.Collections.Generic.List[string]'
    $probe = { param($path) $seen.Add($path); return $versions[$path] }
    $old = Add-Candidate 'jdk-8-last' '1.8.0.382'
    $minor = Add-Candidate 'jdk-17-lexically-last' '17.0.9.0'
    $newer = Add-Candidate 'jdk-17-lexically-first' '17.0.20.1' 1
    $broken = Add-Candidate 'jdk-broken' ''
    $path = Add-Candidate 'path-runtime' '27.0.0.0'
    $selected = Find-RealmSharkJavaw -ToolsDirectory $fixtureRoot -Probe $probe -PathJavaw $path
    if ($selected -ne $newer -or $seen.Count -ne 4) { throw 'Must probe all local candidates and choose numeric newest, ignoring PATH when local qualifies' }
    $ea = Add-Candidate 'jdk-21-ea' '21.0.0.0' 30 $false
    $ga = Add-Candidate 'jdk-21-ga' '21.0.0.0' 1
    if ((Find-RealmSharkJavaw $fixtureRoot $probe $path) -ne $ga) { throw 'A release must win over a same-version early access build' }
    $patch = Add-Candidate 'jdk-21-patch' '21.0.1.0' 1
    $build = Add-Candidate 'jdk-21-new-build' '21.0.1.0' 2
    if ((Find-RealmSharkJavaw $fixtureRoot $probe $path) -ne $build) { throw 'Newest patch/build must win independent of folder order' }
    foreach ($key in @($versions.Keys)) { if ($key -ne (Join-Path (Split-Path $path) 'java.exe')) { $versions[$key] = $null } }
    if ((Find-RealmSharkJavaw $fixtureRoot $probe $path) -ne $path) { throw 'PATH fallback failed when all local candidates were unusable' }
    $versions[(Join-Path (Split-Path $path) 'java.exe')] = [pscustomobject]@{Version=[version]'1.8.0.382'; Build=0; Stable=$true}
    $rejected = $false
    try { Find-RealmSharkJavaw $fixtureRoot $probe $path | Out-Null } catch { $rejected = $_.Exception.Message -like '*needs Java 17*' }
    if (!$rejected) { throw 'Old PATH runtime was not rejected' }
    if ($JavaHome) {
        $runtime = Get-RealmSharkJavaRuntime -JavaPath (Join-Path $JavaHome 'bin\java.exe')
        if ($null -eq $runtime -or $runtime.Version.Major -lt 17) { throw 'Real development runtime probe failed' }
    }
    Write-Output 'PASS: all local candidates probed; numeric version, release/build ordering, broken candidates, PATH fallback and old-runtime rejection verified.'
} finally {
    $resolved = [System.IO.Path]::GetFullPath($fixtureRoot)
    $temp = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath()).TrimEnd('\') + '\'
    if (!$resolved.StartsWith($temp, [StringComparison]::OrdinalIgnoreCase) -or (Split-Path -Leaf $resolved) -notlike 'realmshark-java-*') { throw 'Refusing fixture cleanup outside temp directory' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
