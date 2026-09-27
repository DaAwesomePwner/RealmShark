# Runtime selection for the source launcher. Dot-sourcing does not start Java.
function Get-RealmSharkJavaRuntime {
    param([Parameter(Mandatory = $true)][string] $JavaPath)
    $previousPreference = $ErrorActionPreference
    try {
        # Java writes settings to stderr; Windows PowerShell 5.1 wraps that stream.
        $ErrorActionPreference = 'Continue'
        $output = & $JavaPath -XshowSettings:properties -version 2>&1
        if ($LASTEXITCODE -ne 0) { return $null }
        $properties = @{}
        foreach ($line in $output) {
            if ("$line" -match '^\s*(java\.(?:version|runtime\.version)) = (\S+)') {
                $properties[$Matches[1]] = $Matches[2]
            }
        }
        $raw = $properties['java.version']
        if ($raw -notmatch '^(\d+)(?:\.(\d+))?(?:\.(\d+))?(?:\.(\d+))?(?:[+_-]|$)') { return $null }
        $parts = @(1..4 | ForEach-Object { if ($Matches[$_]) { [int]$Matches[$_] } else { 0 } })
        $version = New-Object System.Version($parts[0], $parts[1], $parts[2], $parts[3])
        $runtime = $properties['java.runtime.version']
        $build = if ($runtime -match '\+(\d+)') { [long]$Matches[1] } else { 0L }
        [pscustomobject]@{ Version = $version; Build = $build; Stable = ($raw -notmatch '-(?:ea|internal)'); JavaPath = $JavaPath }
    } catch {
        # An unreadable or broken candidate must not prevent trying other JDKs.
        return $null
    } finally { $ErrorActionPreference = $previousPreference }
}

function Find-RealmSharkJavaw {
    param(
        [Parameter(Mandatory = $true)][string] $ToolsDirectory,
        [scriptblock] $Probe = { param($path) Get-RealmSharkJavaRuntime -JavaPath $path },
        [string] $PathJavaw
    )
    $candidates = @(foreach ($jdk in Get-ChildItem -LiteralPath $ToolsDirectory -Directory -Filter 'jdk-*' -ErrorAction SilentlyContinue) {
        $java = Join-Path $jdk.FullName 'bin\java.exe'
        $javaw = Join-Path $jdk.FullName 'bin\javaw.exe'
        if (!(Test-Path -LiteralPath $java -PathType Leaf) -or !(Test-Path -LiteralPath $javaw -PathType Leaf)) { continue }
        $runtime = & $Probe $java
        if ($null -ne $runtime -and $runtime.Version.Major -ge 17) {
            [pscustomobject]@{ Version = $runtime.Version; Stable = $runtime.Stable; Build = $runtime.Build; Javaw = $javaw }
        }
    })
    $newest = $candidates | Sort-Object -Property @{Expression = {$_.Version}; Descending = $true},
        @{Expression = {$_.Stable}; Descending = $true}, @{Expression = {$_.Build}; Descending = $true}, Javaw | Select-Object -First 1
    if ($null -ne $newest) { return $newest.Javaw }

    # Only consider PATH when no project-local JDK qualifies. Probe javaw's sibling,
    # not an unrelated java.exe elsewhere on PATH.
    if (!$PathJavaw) {
        $command = Get-Command javaw.exe -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($command) { $PathJavaw = $command.Source }
    }
    if ($PathJavaw -and (Test-Path -LiteralPath $PathJavaw -PathType Leaf)) {
        $java = Join-Path (Split-Path -Parent $PathJavaw) 'java.exe'
        if (Test-Path -LiteralPath $java -PathType Leaf) {
            $runtime = & $Probe $java
            if ($null -ne $runtime -and $runtime.Version.Major -ge 17) { return $PathJavaw }
        }
    }
    throw 'RealmShark needs Java 17 or newer. No compatible runtime was found in .tools\jdk-* or on PATH.'
}
