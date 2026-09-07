function New-RealmSharkRuntimeJar {
    param(
        [Parameter(Mandatory = $true)][string] $SourceJar,
        [Parameter(Mandatory = $true)][string] $RuntimeDirectory
    )

    # Keep running JVMs away from Gradle's mutable output. Java loads many classes lazily.
    [System.IO.Directory]::CreateDirectory($RuntimeDirectory) | Out-Null
    $stagingJar = Join-Path $RuntimeDirectory ([guid]::NewGuid().ToString('N') + '.tmp')
    try {
        # Deny concurrent writers while copying; a build in progress must finish first.
        $source = [System.IO.File]::Open($SourceJar, 'Open', 'Read', 'Read')
        try {
            $destination = [System.IO.File]::Open($stagingJar, 'CreateNew', 'Write', 'None')
            try { $source.CopyTo($destination) }
            finally { $destination.Dispose() }
        } finally { $source.Dispose() }

        $digest = (Get-FileHash -LiteralPath $stagingJar -Algorithm SHA256).Hash.ToLowerInvariant()
        $runtimeJar = Join-Path $RuntimeDirectory ('RealmShark-' + $digest + '.jar')
        if ([System.IO.File]::Exists($runtimeJar)) {
            if ((Get-FileHash -LiteralPath $runtimeJar -Algorithm SHA256).Hash.ToLowerInvariant() -ne $digest) {
                throw 'The saved runtime JAR is damaged. Close RealmShark and remove its .runtime folder before retrying.'
            }
        } else {
            try { [System.IO.File]::Move($stagingJar, $runtimeJar) }
            catch {
                # Two simultaneous launches may publish the same immutable build.
                if (![System.IO.File]::Exists($runtimeJar) -or
                    (Get-FileHash -LiteralPath $runtimeJar -Algorithm SHA256).Hash.ToLowerInvariant() -ne $digest) { throw }
            }
        }
        return $runtimeJar
    } finally {
        # Delete only this invocation's staging file; never modify a published runtime.
        if ([System.IO.File]::Exists($stagingJar)) { [System.IO.File]::Delete($stagingJar) }
    }
}
