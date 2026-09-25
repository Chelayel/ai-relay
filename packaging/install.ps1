# AI Relay installer for Windows. No Java, no administrator rights:
#
#   irm https://raw.githubusercontent.com/Chelayel/ai-relay/main/packaging/install.ps1 | iex
#
# Downloads the self-contained build (it carries its own Java runtime) into
# %LOCALAPPDATA%\Programs\airelay and adds it to your user PATH. Re-run to upgrade.
# $env:AIRELAY_VERSION = 'v1.2.3' pins a release.
$ErrorActionPreference = 'Stop'

$repo = 'Chelayel/ai-relay'
$asset = 'airelay-windows-x64.zip'
$root = Join-Path $env:LOCALAPPDATA 'Programs\airelay'
$url = if ($env:AIRELAY_VERSION) {
    "https://github.com/$repo/releases/download/$($env:AIRELAY_VERSION)/$asset"
} else {
    "https://github.com/$repo/releases/latest/download/$asset"
}

$tmp = Join-Path ([IO.Path]::GetTempPath()) ("airelay-" + [Guid]::NewGuid())
New-Item -ItemType Directory -Path $tmp | Out-Null
try {
    Write-Host "Downloading $asset…"
    $zip = Join-Path $tmp $asset
    Invoke-WebRequest -Uri $url -OutFile $zip -UseBasicParsing

    # Unpack first and swap after, so a failed download never leaves half an install.
    Expand-Archive -Path $zip -DestinationPath (Join-Path $tmp 'unpacked')
    New-Item -ItemType Directory -Path (Split-Path $root) -Force | Out-Null
    if (Test-Path $root) {
        # Rename first: it fails whole if a running airelay holds a file, where a
        # recursive delete would stop half way and leave a broken install.
        $old = "$root.old"
        if (Test-Path $old) { Remove-Item -Recurse -Force $old -ErrorAction SilentlyContinue }
        try { Rename-Item $root $old -ErrorAction Stop }
        catch { throw "airelay is running (an IDE panel or a terminal). Close it and run the installer again." }
        Move-Item (Join-Path $tmp 'unpacked\airelay') $root
        Remove-Item -Recurse -Force $old -ErrorAction SilentlyContinue
    } else {
        Move-Item (Join-Path $tmp 'unpacked\airelay') $root
    }
} finally {
    Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
}

if (-not (Test-Path (Join-Path $root 'airelay.exe'))) { throw "The download did not contain airelay.exe." }

# An airelay from another route (Scoop, the .msi) stays where it is — this
# script cannot uninstall what a package manager owns — and if it comes earlier
# on PATH it is the one that keeps running. Name it and say how to remove it.
foreach ($d in ($env:Path -split ';' | Where-Object { $_ })) {
    foreach ($name in 'airelay.exe', 'airelay.cmd', 'airelay.bat') {
        $other = Join-Path $d $name
        if (-not (Test-Path $other -PathType Leaf)) { continue }
        if ($other.TrimEnd('\').StartsWith($root.TrimEnd('\'), 'OrdinalIgnoreCase')) { continue }
        $how = if ($other -like '*\scoop\*') {
            "Scoop. Remove it with:  scoop uninstall airelay"
        } elseif ($other.StartsWith((Join-Path $env:LOCALAPPDATA 'airelay\'), 'OrdinalIgnoreCase')) {
            "the Windows installer (.msi). Remove it from Settings > Apps > Installed apps > airelay > Uninstall"
        } else {
            "something else. Remove it by hand if you no longer want it."
        }
        Write-Host ""
        Write-Host "Note: another airelay is installed at $other, from $how"
    }
}

# The user PATH, not the machine one: no elevation, and it survives reboots.
$userPath = [string][Environment]::GetEnvironmentVariable('Path', 'User')
if (($userPath -split ';') -notcontains $root) {
    [Environment]::SetEnvironmentVariable('Path', (($userPath.TrimEnd(';') + ';' + $root).TrimStart(';')), 'User')
    Write-Host "Added $root to your PATH. Open a new terminal, then run: airelay"
} else {
    Write-Host "Installed. Run: airelay"
}
