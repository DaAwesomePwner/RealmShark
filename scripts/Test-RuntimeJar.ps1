param([Parameter(Mandatory = $true)][string] $JavaHome)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'RuntimeJar.ps1')
$projectDirectory = Split-Path -Parent $PSScriptRoot
$fixture = Join-Path $projectDirectory ('build\runtime-test-' + [guid]::NewGuid().ToString('N'))
[System.IO.Directory]::CreateDirectory($fixture) | Out-Null
$classes = Join-Path $fixture 'classes'
[System.IO.Directory]::CreateDirectory($classes) | Out-Null
$source = Join-Path $fixture 'RuntimeProbe.java'
@'
import java.nio.file.*;
public class RuntimeProbe {
    public static void main(String[] args) throws Exception {
        Path ready = Paths.get(args[0]), resume = Paths.get(args[1]);
        Files.write(ready, new byte[0]);
        long deadline = System.nanoTime() + 15_000_000_000L;
        while (!Files.exists(resume)) {
            if (System.nanoTime() > deadline) throw new AssertionError("Timed out");
            Thread.sleep(25);
        }
        Object result = Class.forName("LatePayload").getMethod("value").invoke(null);
        if (!"original build".equals(result)) throw new AssertionError("Loaded changed build");
        System.out.println("Late class loaded from original runtime after build replacement");
    }
}
'@ | Set-Content -LiteralPath $source
'public class LatePayload { public static String value() { return "original build"; } }' | Set-Content -LiteralPath (Join-Path $fixture 'LatePayload.java')
& (Join-Path $JavaHome 'bin\javac.exe') -d $classes $source (Join-Path $fixture 'LatePayload.java')
if ($LASTEXITCODE -ne 0) { throw 'Fixture compilation failed' }
$buildJar = Join-Path $fixture 'build.jar'
& (Join-Path $JavaHome 'bin\jar.exe') cfe $buildJar RuntimeProbe -C $classes .
if ($LASTEXITCODE -ne 0) { throw 'Fixture JAR creation failed' }
$runtimeDirectory = Join-Path $fixture 'runtime'
$runtimeJar = New-RealmSharkRuntimeJar $buildJar $runtimeDirectory
if ((New-RealmSharkRuntimeJar $buildJar $runtimeDirectory) -ne $runtimeJar) { throw 'Identical builds should reuse the same immutable runtime' }
$ready = Join-Path $fixture 'ready'
$resume = Join-Path $fixture 'resume'
$output = Join-Path $fixture 'output.txt'
$errors = Join-Path $fixture 'errors.txt'
$process = Start-Process -FilePath (Join-Path $JavaHome 'bin\java.exe') -ArgumentList "-jar `"$runtimeJar`" `"$ready`" `"$resume`"" -PassThru -WindowStyle Hidden -RedirectStandardOutput $output -RedirectStandardError $errors
$processHandle = $process.Handle # Retain the handle so Windows PowerShell can read ExitCode after exit.
try {
    $deadline = [DateTime]::UtcNow.AddSeconds(10)
    while (!(Test-Path -LiteralPath $ready)) {
        if ($process.HasExited -or [DateTime]::UtcNow -gt $deadline) { throw 'Runtime probe did not become ready' }
        Start-Sleep -Milliseconds 25
    }
    # Rebuild the source without LatePayload, while the first JVM has not loaded it yet.
    & (Join-Path $JavaHome 'bin\jar.exe') cfe $buildJar RuntimeProbe -C $classes RuntimeProbe.class
    if ($LASTEXITCODE -ne 0) { throw 'Replacing build JAR failed' }
    $secondRuntime = New-RealmSharkRuntimeJar $buildJar $runtimeDirectory
    if ($secondRuntime -eq $runtimeJar) { throw 'Changed builds must have distinct runtime paths' }
    [System.IO.File]::WriteAllText($resume, '')
    if (!$process.WaitForExit(10000)) { throw 'Runtime probe timed out' }
    if ($process.ExitCode -ne 0) { throw ('Runtime probe failed: ' + (Get-Content -LiteralPath $errors -Raw)) }
    Get-Content -LiteralPath $output
    # A partial overwrite must be detected, never silently repaired beneath a running JVM.
    [System.IO.File]::WriteAllText($secondRuntime, 'damaged fixture')
    $detected = $false
    try { New-RealmSharkRuntimeJar $buildJar $runtimeDirectory | Out-Null }
    catch { if ($_.Exception.Message -match 'damaged') { $detected = $true } else { throw } }
    if (!$detected) { throw 'Damaged runtime was not detected' }
    'PASS: build isolation, identical-build reuse, distinct-build paths, and corruption detection'
} finally {
    if (!$process.HasExited) { $process.Kill(); $process.WaitForExit() }
    $process.Dispose()
}
