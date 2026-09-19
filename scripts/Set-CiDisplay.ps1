$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

# This changes the current desktop. Never invoke it on a developer workstation.
if ($env:GITHUB_ACTIONS -ne 'true' -or $env:RUNNER_OS -ne 'Windows' -or $env:OS -ne 'Windows_NT') {
    throw 'Set-CiDisplay.ps1 may only run on a Windows GitHub Actions runner.'
}
if (!$env:JAVA_HOME) { throw 'Set up JDK 17 before preparing the CI display.' }
$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
$probe = Join-Path $PSScriptRoot 'validation\CheckUiDisplay.java'
if (!(Test-Path -LiteralPath $java) -or !(Test-Path -LiteralPath $probe)) {
    throw 'The Java executable or CheckUiDisplay.java is missing.'
}

# Unicode Win32 layouts; use only driver-enumerated modes and immediate changes.
Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;

public static class RealmSharkCiDisplay {
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    public struct DisplayDevice {
        public uint Size;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)] public string DeviceName;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 128)] public string DeviceString;
        public uint StateFlags;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 128)] public string DeviceID;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 128)] public string DeviceKey;
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    public struct DevMode {
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)] public string DeviceName;
        public ushort SpecVersion, DriverVersion, Size, DriverExtra;
        public uint Fields;
        public int PositionX, PositionY;
        public uint DisplayOrientation, DisplayFixedOutput;
        public short Color, Duplex, YResolution, TTOption, Collate;
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)] public string FormName;
        public ushort LogPixels;
        public uint BitsPerPel, PelsWidth, PelsHeight, DisplayFlags, DisplayFrequency;
        public uint ICMMethod, ICMIntent, MediaType, DitherType;
        public uint Reserved1, Reserved2, PanningWidth, PanningHeight;
    }

    [DllImport("user32.dll", EntryPoint = "EnumDisplayDevicesW", CharSet = CharSet.Unicode, ExactSpelling = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static extern bool EnumDisplayDevices(IntPtr device, uint index, ref DisplayDevice result, uint flags);

    [DllImport("user32.dll", EntryPoint = "EnumDisplaySettingsW", CharSet = CharSet.Unicode, ExactSpelling = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static extern bool EnumDisplaySettings(string device, int index, ref DevMode result);

    [DllImport("user32.dll", EntryPoint = "ChangeDisplaySettingsExW", CharSet = CharSet.Unicode, ExactSpelling = true)]
    public static extern int ChangeDisplaySettingsEx(string device, ref DevMode mode, IntPtr window, uint flags, IntPtr parameter);
}
'@

function Get-CurrentMode([string] $DeviceName) {
    $mode = New-Object RealmSharkCiDisplay+DevMode
    $mode.Size = [Runtime.InteropServices.Marshal]::SizeOf($mode)
    if (![RealmSharkCiDisplay]::EnumDisplaySettings($DeviceName, -1, [ref] $mode)) {
        throw "EnumDisplaySettings(ENUM_CURRENT_SETTINGS) failed for $DeviceName."
    }
    return $mode
}

function Format-Mode($Mode) {
    return ('{0}x{1}, {2} bpp, {3} Hz' -f $Mode.PelsWidth, $Mode.PelsHeight, $Mode.BitsPerPel, $Mode.DisplayFrequency)
}

$primary = $null
for ($index = 0; ; $index++) {
    $device = New-Object RealmSharkCiDisplay+DisplayDevice
    $device.Size = [Runtime.InteropServices.Marshal]::SizeOf($device)
    if (![RealmSharkCiDisplay]::EnumDisplayDevices([IntPtr]::Zero, $index, [ref] $device, 0)) { break }
    # DISPLAY_DEVICE_ATTACHED_TO_DESKTOP | DISPLAY_DEVICE_PRIMARY_DEVICE
    if (($device.StateFlags -band 5) -eq 5) { $primary = $device; break }
}
if ($null -eq $primary) { throw 'No attached primary display exists in the runner session.' }
$deviceName = $primary.DeviceName
$current = Get-CurrentMode $deviceName
$modes = @(for ($index = 0; ; $index++) {
    $mode = New-Object RealmSharkCiDisplay+DevMode
    $mode.Size = [Runtime.InteropServices.Marshal]::SizeOf($mode)
    if (![RealmSharkCiDisplay]::EnumDisplaySettings($deviceName, $index, [ref] $mode)) { break }
    $mode
})
$available = ($modes | ForEach-Object { Format-Mode $_ } | Sort-Object -Unique) -join '; '
Write-Host "Primary display: $deviceName ($($primary.DeviceString))"
Write-Host "Current mode: $(Format-Mode $current)"
Write-Host "Available modes: $available"

try {
    # Full desktop fixtures need 1240x800 logical pixels, plus taskbar margin.
    # Hosted Hyper-V displays top out at 1920x1080; scaled tasks run locally.
    $minimumWidth = 1600
    $minimumHeight = 900
    if ($current.PelsWidth -ge $minimumWidth -and $current.PelsHeight -ge $minimumHeight) {
        Write-Host 'Keeping the current display mode.'
    } else {
        $candidates = @($modes | Where-Object {
            $_.PelsWidth -ge $minimumWidth -and $_.PelsHeight -ge $minimumHeight -and $_.BitsPerPel -eq 32
        } | Sort-Object { [long] $_.PelsWidth * $_.PelsHeight }, PelsWidth, PelsHeight, DisplayFrequency)
        $selected = $null
        foreach ($candidate in $candidates) {
            # CDS_TEST = 2. Only DISP_CHANGE_SUCCESSFUL = 0 is acceptable.
            $result = [RealmSharkCiDisplay]::ChangeDisplaySettingsEx($deviceName, [ref] $candidate, [IntPtr]::Zero, 2, [IntPtr]::Zero)
            Write-Host "CDS_TEST $(Format-Mode $candidate): $result"
            if ($result -eq 0) { $selected = $candidate; break }
        }
        if ($null -eq $selected) { throw 'No supported mode can provide the required 1600x900 physical desktop.' }
        $result = [RealmSharkCiDisplay]::ChangeDisplaySettingsEx($deviceName, [ref] $selected, [IntPtr]::Zero, 0, [IntPtr]::Zero)
        Write-Host "Apply $(Format-Mode $selected): $result"
        if ($result -ne 0) { throw "Display change failed with code $result (0 required; 1 means restart required)." }
        $current = Get-CurrentMode $deviceName
        Write-Host "Readback: $(Format-Mode $current)"
        if ($current.PelsWidth -ne $selected.PelsWidth -or $current.PelsHeight -ne $selected.PelsHeight -or $current.BitsPerPel -ne $selected.BitsPerPel) {
            throw "Display readback did not match the selected mode: $(Format-Mode $selected)."
        }
    }

    & $java '-Dsun.java2d.uiScale=1' $probe '1'
    if ($LASTEXITCODE -ne 0) { throw "The 1x Java display probe failed (exit $LASTEXITCODE)." }
    Write-Host 'PASS: CI desktop supports a real 1240x800 logical window at 100%.'
} catch {
    throw "CI display setup failed: $($_.Exception.Message) Available modes: $available. A runner display supporting the required native window sizes is necessary."
}
