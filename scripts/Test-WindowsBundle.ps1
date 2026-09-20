param([string] $ZipPath, [string] $BundlePath, [string] $BuildDirectory,
      [string] $JavaHome = $env:JAVA_HOME, [string] $ExpectedJarVersion = 'v1.2.3')

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if (!$JavaHome) { $JavaHome = (Get-ChildItem "$projectRoot/.tools" -Directory -Filter 'jdk-17*' -ErrorAction SilentlyContinue | Select-Object -First 1).FullName }
if (!$JavaHome -or !(Test-Path -LiteralPath "$JavaHome/bin/javac.exe") -or !(Test-Path -LiteralPath "$JavaHome/bin/java.exe")) { throw 'Package verification requires -JavaHome or JAVA_HOME pointing to JDK 17.' }
$JavaHome = (Resolve-Path -LiteralPath $JavaHome).Path
if (!$ZipPath) { $ZipPath = Join-Path $projectRoot 'build/share/RealmShark-Windows-x64.zip' }
$ZipPath = (Resolve-Path -LiteralPath $ZipPath).Path
if ($BundlePath) { $BundlePath = (Resolve-Path -LiteralPath $BundlePath).Path }
if (!$BuildDirectory) { $BuildDirectory = Join-Path $projectRoot 'build' }
$BuildDirectory = (Resolve-Path -LiteralPath $BuildDirectory).Path
$iconPath = Join-Path $BuildDirectory 'generated/branding/icon/realmshark.ico'
$jarPath = Join-Path $BuildDirectory 'libs/RealmShark-v1.2.3.jar'
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Assert-RealmSharkPackage {
    param([bool] $Condition, [string] $Message)
    if (!$Condition) { throw "RealmShark package verification failed: $Message" }
}

function Get-EntryHash {
    param([System.IO.Compression.ZipArchiveEntry] $Entry)
    $stream = $Entry.Open()
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try { return [BitConverter]::ToString($sha.ComputeHash($stream)).Replace('-', '') }
    finally { $sha.Dispose(); $stream.Dispose() }
}

function Read-EntryText {
    param([System.IO.Compression.ZipArchiveEntry] $Entry)
    $reader = [System.IO.StreamReader]::new($Entry.Open())
    try { return $reader.ReadToEnd() }
    finally { $reader.Dispose() }
}

# Read PE resources as data only; the JAR probe uses the development JDK, never the bundled launcher/runtime.
if (!('RealmSharkPackage.IconResources' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.ComponentModel;
using System.IO;
using System.Runtime.InteropServices;

namespace RealmSharkPackage {
    public static class IconResources {
        private delegate bool ResourceCallback(IntPtr module, IntPtr type, IntPtr name, IntPtr data);
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern IntPtr LoadLibraryEx(string path, IntPtr file, uint flags);
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern bool EnumResourceNames(IntPtr module, IntPtr type, ResourceCallback callback, IntPtr data);
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern IntPtr FindResource(IntPtr module, IntPtr name, IntPtr type);
        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern IntPtr LoadResource(IntPtr module, IntPtr resource);
        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern uint SizeofResource(IntPtr module, IntPtr resource);
        [DllImport("kernel32.dll")]
        private static extern IntPtr LockResource(IntPtr resource);
        [DllImport("kernel32.dll")]
        private static extern bool FreeLibrary(IntPtr module);

        private static byte[] ReadResource(IntPtr module, IntPtr name, int type) {
            IntPtr resource = FindResource(module, name, new IntPtr(type));
            if (resource == IntPtr.Zero) throw new Win32Exception(Marshal.GetLastWin32Error());
            int size = checked((int)SizeofResource(module, resource));
            IntPtr bytes = LockResource(LoadResource(module, resource));
            if (size == 0 || bytes == IntPtr.Zero) throw new InvalidDataException("Empty EXE icon resource");
            byte[] result = new byte[size];
            Marshal.Copy(bytes, result, 0, size);
            return result;
        }

        public static int Verify(string executable, string iconPath) {
            byte[] ico = File.ReadAllBytes(iconPath);
            if (ico.Length < 6 || BitConverter.ToUInt16(ico, 0) != 0 || BitConverter.ToUInt16(ico, 2) != 1)
                throw new InvalidDataException("Invalid canonical RealmShark ICO");
            int count = BitConverter.ToUInt16(ico, 4);
            if (count == 0 || ico.Length < 6 + count * 16)
                throw new InvalidDataException("Missing canonical RealmShark icon images");
            IntPtr module = LoadLibraryEx(executable, IntPtr.Zero, 2); // LOAD_LIBRARY_AS_DATAFILE
            if (module == IntPtr.Zero) throw new Win32Exception(Marshal.GetLastWin32Error());
            try {
                int groups = 0;
                Exception failure = null;
                ResourceCallback callback = delegate(IntPtr handle, IntPtr type, IntPtr name, IntPtr data) {
                    try {
                        byte[] group = ReadResource(handle, name, 14); // RT_GROUP_ICON
                        if (group.Length != 6 + count * 14)
                            throw new InvalidDataException("EXE icon size count differs from canonical ICO");
                        for (int b = 0; b < 6; b++)
                            if (group[b] != ico[b]) throw new InvalidDataException("EXE icon directory differs from canonical ICO");
                        for (int i = 0; i < count; i++) {
                            int source = 6 + i * 16, target = 6 + i * 14;
                            for (int b = 0; b < 12; b++)
                                if (group[target + b] != ico[source + b])
                                    throw new InvalidDataException("EXE icon dimensions/format differ from canonical ICO");
                            int length = checked((int)BitConverter.ToUInt32(ico, source + 8));
                            int offset = checked((int)BitConverter.ToUInt32(ico, source + 12));
                            if (offset < 6 + count * 16 || length <= 0 || length > ico.Length - offset)
                                throw new InvalidDataException("Invalid canonical ICO image bounds");
                            int id = BitConverter.ToUInt16(group, target + 12);
                            byte[] image = ReadResource(handle, new IntPtr(id), 3); // RT_ICON
                            if (image.Length != length) throw new InvalidDataException("EXE icon image length differs from canonical ICO");
                            for (int b = 0; b < length; b++)
                                if (image[b] != ico[offset + b])
                                    throw new InvalidDataException("EXE icon pixels differ from canonical ICO (default Java icon or stale branding)");
                        }
                        groups++;
                        return true;
                    } catch (Exception error) { failure = error; return false; }
                };
                bool enumerated = EnumResourceNames(module, new IntPtr(14), callback, IntPtr.Zero);
                if (failure != null) throw failure;
                if (!enumerated) throw new Win32Exception(Marshal.GetLastWin32Error());
                if (groups == 0) throw new InvalidDataException("RealmShark EXE has no icon group");
                return count;
            } finally { FreeLibrary(module); }
        }
    }
}
'@
}

$expectedDigest = (Get-FileHash -LiteralPath $ZipPath -Algorithm SHA256).Hash
$checksum = [System.IO.File]::ReadAllText("$ZipPath.sha256").Trim()
Assert-RealmSharkPackage ($checksum -eq "$expectedDigest  RealmShark-Windows-x64.zip") 'ZIP SHA-256 does not match its sidecar.'
$archive = [System.IO.Compression.ZipFile]::OpenRead($ZipPath)
$temporary = Join-Path $BuildDirectory ('package-verification-' + [guid]::NewGuid().ToString('N'))
try {
    $entries = @{}
    $rootFiles = @('RealmShark.exe', 'LICENSE.md', 'rotmg_loot_drops_updated.csv', 'LOOT-CATALOG-LICENSE.txt', 'UNITYPY-LICENSE.txt', 'BRIDGE.md', 'READ-ME-FIRST.md', 'Preview-RealmShark.cmd')
    $appFiles = @('app/.jpackage.xml', 'app/RealmShark.cfg', 'app/portable-launcher.jar', 'app/RealmShark-v1.2.3.jar', 'app/RealmShark.ico')
    foreach ($entry in $archive.Entries) {
        $name = $entry.FullName
        Assert-RealmSharkPackage ($name.StartsWith('RealmShark/') -and $name -notmatch '\\|(^|/)\.\.?(/|$)|:') "Unexpected ZIP path: $name"
        if ($name.EndsWith('/')) {
            Assert-RealmSharkPackage ($name -in @('RealmShark/', 'RealmShark/app/', 'RealmShark/runtime/') -or $name.StartsWith('RealmShark/runtime/')) "Unexpected ZIP directory: $name"
            continue
        }
        $relative = $name.Substring('RealmShark/'.Length)
        Assert-RealmSharkPackage (!$entries.ContainsKey($relative)) "Duplicate ZIP entry: $relative"
        Assert-RealmSharkPackage ($relative -in $rootFiles -or $relative -in $appFiles -or $relative.StartsWith('runtime/')) "Non-public or unexpected payload: $relative"
        $entries[$relative] = $entry
        if ($BundlePath) {
            $file = Join-Path $BundlePath $relative
            Assert-RealmSharkPackage ((Get-EntryHash $entry) -eq (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash) "ZIP differs from staged bundle: $relative"
        }
    }
    foreach ($required in $rootFiles + $appFiles + @('runtime/bin/java.exe', 'runtime/bin/javaw.exe')) {
        Assert-RealmSharkPackage ($entries.ContainsKey($required)) "Missing required file: $required"
    }
    Assert-RealmSharkPackage (@($entries.Keys | Where-Object { $_.StartsWith('runtime/legal/') }).Count -gt 0) 'Missing Java runtime license notices.'
    if ($BundlePath) {
        $bundleFiles = @(Get-ChildItem -LiteralPath $BundlePath -File -Recurse -Force)
        Assert-RealmSharkPackage ($bundleFiles.Count -eq $entries.Count) 'ZIP omits files from the staged bundle.'
    }

    # Verify current build outputs and explicit public inputs, not just ZIP self-consistency.
    $sources = @{
        'app/RealmShark-v1.2.3.jar' = $jarPath
        'app/RealmShark.ico' = $iconPath
        'LICENSE.md' = (Join-Path $projectRoot 'LICENSE.md')
        'rotmg_loot_drops_updated.csv' = (Join-Path $projectRoot 'rotmg_loot_drops_updated.csv')
        'LOOT-CATALOG-LICENSE.txt' = (Join-Path $projectRoot 'docs/LOOT-CATALOG-LICENSE.txt')
        'UNITYPY-LICENSE.txt' = (Join-Path $projectRoot 'docs/UNITYPY-LICENSE.txt')
        'BRIDGE.md' = (Join-Path $projectRoot 'docs/BRIDGE.md')
        'READ-ME-FIRST.md' = (Join-Path $projectRoot 'docs/WINDOWS-BUNDLE.md')
    }
    foreach ($relative in $sources.Keys) {
        Assert-RealmSharkPackage ((Get-EntryHash $entries[$relative]) -eq (Get-FileHash -LiteralPath $sources[$relative] -Algorithm SHA256).Hash) "Stale packaged file: $relative"
    }
    $jarStream = $entries['app/RealmShark-v1.2.3.jar'].Open()
    try {
        $jar = [System.IO.Compression.ZipArchive]::new($jarStream, [System.IO.Compression.ZipArchiveMode]::Read)
        try {
            $manifest = Read-EntryText $jar.GetEntry('META-INF/MANIFEST.MF')
            Assert-RealmSharkPackage ($manifest -match '(?m)^Main-Class: realmshark\.RealmShark\r?$') 'Application JAR must launch realmshark.RealmShark.'
            Assert-RealmSharkPackage ($null -ne $jar.GetEntry('realmshark/RealmShark.class')) 'RealmShark entrypoint is absent from the application JAR.'
            $unityNotice = $jar.GetEntry('META-INF/licenses/UNITYPY-LICENSE.txt')
            Assert-RealmSharkPackage ($null -ne $unityNotice) 'Missing UnityPy notice in the application JAR.'
            Assert-RealmSharkPackage ((Get-EntryHash $unityNotice) -eq (Get-FileHash -LiteralPath $sources['UNITYPY-LICENSE.txt'] -Algorithm SHA256).Hash) 'Stale UnityPy notice in the application JAR.'
        } finally { $jar.Dispose() }
    } finally { $jarStream.Dispose() }
    $configuration = Read-EntryText $entries['app/RealmShark.cfg']
    Assert-RealmSharkPackage ($configuration -match '(?m)^app.mainclass=PortableLauncher\r?$' -and $configuration.Contains('portable-launcher.jar')) 'Native launcher must use the independent portable JAR wrapper.'

    [System.IO.Directory]::CreateDirectory($temporary) | Out-Null
    $packagedJar = Join-Path $temporary 'RealmShark-v1.2.3.jar'
    [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entries['app/RealmShark-v1.2.3.jar'], $packagedJar)
    & "$JavaHome/bin/java.exe" '-Djava.awt.headless=true' '--source' '17' (Join-Path $PSScriptRoot 'validation/BuildContractProbe.java') $packagedJar $ExpectedJarVersion 'v1.9.2' 'v1.9.1' $sources['UNITYPY-LICENSE.txt']
    Assert-RealmSharkPackage ($LASTEXITCODE -eq 0) 'Packaged JAR version, inlined identity, Java 8 classes or notice failed verification.'
    $executable = Join-Path $temporary 'RealmShark.exe'
    [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entries['RealmShark.exe'], $executable)
    $version = [System.Diagnostics.FileVersionInfo]::GetVersionInfo($executable)
    $copyright = [regex]::Match([System.IO.File]::ReadAllText($sources['LICENSE.md']), '(?im)^Copyright[^\r\n]*').Value.Trim()
    Assert-RealmSharkPackage ($version.ProductName -eq 'RealmShark') 'EXE ProductName is not RealmShark.'
    Assert-RealmSharkPackage ($version.FileDescription -eq 'RealmShark desktop companion for Realm of the Mad God') 'EXE description is not RealmShark branded.'
    Assert-RealmSharkPackage ($version.CompanyName -eq 'RealmShark contributors') 'EXE vendor is not RealmShark contributors.'
    Assert-RealmSharkPackage ($version.LegalCopyright -eq $copyright) 'EXE copyright differs from LICENSE.md.'
    Assert-RealmSharkPackage ($version.ProductVersion -match '^1\.2\.3(\.0)?$') 'EXE product version is not 1.2.3.'
    $imageCount = [RealmSharkPackage.IconResources]::Verify($executable, $iconPath)
    Write-Output "PASS: RealmShark EXE metadata and all $imageCount icon sizes match the canonical ICO."
    Write-Output 'PASS: RealmShark ZIP checksum, current JAR/entrypoint/icon, public-only layout, documentation, licenses, and native Java commands.'
    Write-Output "Verified RealmShark archive: $ZipPath"
} finally {
    $archive.Dispose()
    if ([System.IO.Directory]::Exists($temporary)) { Remove-Item -LiteralPath $temporary -Recurse -Force }
}
