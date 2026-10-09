<#
.SYNOPSIS
    Builds the Windows executable of Markdown Exporter with jpackage.

.DESCRIPTION
    1. Builds the self-contained jar with Maven (mvn clean package).
    2. Runs jpackage (JDK 21+) to bundle it with a trimmed Java runtime.

    Package types:
      app-image  Portable folder "Markdown Exporter\" containing "Markdown Exporter.exe" (GUI) and
                 "md-exporter-cli.exe" (console, for command line exports), plus a .zip of that folder.
                 Needs nothing but the JDK.
      exe        Installer (setup .exe): Start menu entry, desktop shortcut, .md file association,
                 per-user install (no admin rights). Needs the WiX Toolset 3.x.
      msi        Same as exe, as an MSI package. Needs the WiX Toolset 3.x.
      all        app-image + exe + msi.

    Results are written to target\dist.

.EXAMPLE
    .\scripts\build-exe.ps1
.EXAMPLE
    .\scripts\build-exe.ps1 -Type exe -SkipTests
#>
[CmdletBinding()]
param(
    [ValidateSet('app-image', 'exe', 'msi', 'all')]
    [string] $Type = 'app-image',

    # Skip the unit tests during the Maven build.
    [switch] $SkipTests,

    # Reuse the jar from a previous build instead of running Maven.
    [switch] $NoBuild,

    # Bundle every JDK module instead of the trimmed list below (~30 MB larger, use it if a module is missing).
    [switch] $FullRuntime
)

$ErrorActionPreference = 'Stop'

$AppName     = 'Markdown Exporter'
$MainClass   = 'io.mdexporter.Launcher'
$Vendor      = 'md-exporter'
$Description = 'Export Markdown documents to Word, HTML and PDF'
# Never change: lets new installers upgrade previous installations.
$UpgradeUuid = '0422a249-21b6-4f6b-9127-11e1204d3497'

# Java modules of the bundled runtime. Static part from
#   jdeps --ignore-missing-deps --multi-release 21 --print-module-deps target\md-exporter-*-all.jar
# plus modules that are only loaded at run time (TLS for remote images, locales, charsets, XML DOM for Batik).
$Modules = @(
    'java.base', 'java.compiler', 'java.desktop', 'java.logging', 'java.management', 'java.naming',
    'java.net.http', 'java.prefs', 'java.rmi', 'java.scripting', 'java.security.jgss', 'java.sql', 'java.xml',
    'java.xml.crypto', 'jdk.jfr', 'jdk.jsobject', 'jdk.unsupported', 'jdk.unsupported.desktop',
    'jdk.crypto.ec', 'jdk.charsets', 'jdk.localedata', 'jdk.zipfs', 'jdk.xml.dom', 'jdk.net'
) -join ','

$Root      = Split-Path -Parent $PSScriptRoot
$Packaging = Join-Path $Root 'packaging\windows'
$Target    = Join-Path $Root 'target'
$Dist      = Join-Path $Target 'dist'
$InputDir  = Join-Path $Target 'jpackage-input'

function Write-Step([string] $Message) {
    Write-Host ''
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function Find-Tool([string] $Name) {
    if ($env:JAVA_HOME) {
        $candidate = Join-Path $env:JAVA_HOME "bin\$Name.exe"
        if (Test-Path $candidate) { return $candidate }
    }
    $cmd = Get-Command $Name -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    return $null
}

function Assert-Wix {
    # jpackage of JDK 21 drives WiX 3.x (candle.exe / light.exe).
    if ((Get-Command candle.exe -ErrorAction SilentlyContinue) -and (Get-Command light.exe -ErrorAction SilentlyContinue)) {
        return
    }
    $dirs = @()
    if ($env:WIX) { $dirs += (Join-Path $env:WIX 'bin') }
    $dirs += Get-ChildItem "${env:ProgramFiles(x86)}\WiX Toolset v3*\bin" -Directory -ErrorAction SilentlyContinue |
        Sort-Object FullName -Descending | ForEach-Object { $_.FullName }
    foreach ($dir in $dirs) {
        if ((Test-Path (Join-Path $dir 'candle.exe')) -and (Test-Path (Join-Path $dir 'light.exe'))) {
            $env:PATH = "$dir;$env:PATH"
            Write-Host "Using WiX from $dir"
            return
        }
    }
    throw @"
The WiX Toolset 3.x is required to build '$Type' installers (jpackage of JDK 21 does not support WiX 4+).
Install it, then run this script again:
    winget install WiXToolset.WiXToolset
or download it from https://github.com/wixtoolset/wix3/releases
Without WiX you can still build the portable application:  .\scripts\build-exe.ps1 -Type app-image
"@
}

Push-Location $Root
try {
    # ------------------------------------------------------------------ prerequisites
    $jpackage = Find-Tool 'jpackage'
    if (-not $jpackage) {
        throw 'jpackage not found: install JDK 21 or newer and set JAVA_HOME (or put its bin folder on the PATH).'
    }
    $jdkVersion = (& $jpackage --version).Trim()
    if ([int]($jdkVersion.Split('.')[0]) -lt 21) {
        throw "JDK 21 or newer is required (jpackage $jdkVersion found at $jpackage)."
    }
    Write-Host "jpackage $jdkVersion ($jpackage)"

    $types = if ($Type -eq 'all') { @('app-image', 'exe', 'msi') } else { @($Type) }
    if ($types -contains 'exe' -or $types -contains 'msi') { Assert-Wix }

    # ------------------------------------------------------------------ Maven build
    if (-not $NoBuild) {
        Write-Step 'Building the application jar (mvn clean package)'
        $mvnArgs = @('-B', 'clean', 'package')
        if ($SkipTests) { $mvnArgs += '-DskipTests' }
        & mvn @mvnArgs
        if ($LASTEXITCODE -ne 0) { throw "Maven build failed (exit code $LASTEXITCODE)." }
    }

    $jar = Get-ChildItem (Join-Path $Target '*-all.jar') -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $jar) { throw 'No target\*-all.jar found: run without -NoBuild.' }

    # jpackage wants a purely numeric version: 1.2.3-SNAPSHOT -> 1.2.3
    $projectVersion = $jar.BaseName -replace '^md-exporter-', '' -replace '-all$', ''
    $appVersion = ($projectVersion -replace '[^0-9.].*$', '').TrimEnd('.')
    if (-not $appVersion) { $appVersion = '1.0.0' }
    Write-Host "Application version $appVersion (project version $projectVersion)"

    # Only the shaded jar goes into the package.
    if (Test-Path $InputDir) { Remove-Item $InputDir -Recurse -Force }
    New-Item -ItemType Directory -Force $InputDir | Out-Null
    Copy-Item $jar.FullName $InputDir
    New-Item -ItemType Directory -Force $Dist | Out-Null

    $common = @(
        '--name', $AppName,
        '--app-version', $appVersion,
        '--vendor', $Vendor,
        '--description', $Description,
        '--copyright', "Copyright (c) $((Get-Date).Year) $Vendor",
        '--icon', (Join-Path $Packaging 'md-exporter.ico'),
        '--input', $InputDir,
        '--main-jar', $jar.Name,
        '--main-class', $MainClass,
        '--add-launcher', "md-exporter-cli=$(Join-Path $Packaging 'cli-launcher.properties')",
        '--jlink-options', '--strip-debug --no-man-pages --no-header-files --compress=zip-6',
        '--dest', $Dist
    )
    if (-not $FullRuntime) {
        $common += @('--add-modules', $Modules)
    }

    foreach ($t in $types) {
        Write-Step "Running jpackage --type $t"
        $jpArgs = @("--type", $t) + $common
        if ($t -eq 'app-image') {
            $imageDir = Join-Path $Dist $AppName
            if (Test-Path $imageDir) { Remove-Item $imageDir -Recurse -Force }
        } else {
            $jpArgs += @(
                '--win-per-user-install',
                '--win-dir-chooser',
                '--win-menu', '--win-menu-group', $AppName,
                '--win-shortcut', '--win-shortcut-prompt',
                '--win-upgrade-uuid', $UpgradeUuid,
                '--file-associations', (Join-Path $Packaging 'md-file-association.properties')
            )
        }
        & $jpackage @jpArgs
        if ($LASTEXITCODE -ne 0) { throw "jpackage --type $t failed (exit code $LASTEXITCODE)." }

        if ($t -eq 'app-image') {
            $zip = Join-Path $Dist ("$($AppName -replace ' ', '-')-$appVersion-windows-x64.zip")
            if (Test-Path $zip) { Remove-Item $zip -Force }
            Compress-Archive -Path (Join-Path $Dist $AppName) -DestinationPath $zip
        }
    }

    Write-Step 'Done'
    Get-ChildItem $Dist | ForEach-Object {
        $size = if ($_.PSIsContainer) {
            (Get-ChildItem $_.FullName -Recurse -File | Measure-Object Length -Sum).Sum
        } else { $_.Length }
        '{0,-50} {1,8:N1} MB' -f $_.Name, ($size / 1MB)
    }
    if ($types -contains 'app-image') {
        Write-Host ''
        Write-Host "Run:  `"$(Join-Path $Dist "$AppName\$AppName.exe")`""
        Write-Host "CLI:  `"$(Join-Path $Dist "$AppName\md-exporter-cli.exe")`" --export -f pdf file.md"
    }
}
finally {
    Pop-Location
}
