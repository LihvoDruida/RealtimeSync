[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidatePattern('^1\.21(?:\.(?:[1-9]|10|11))?$')]
    [string]$Profile = '1.21.5',

    [Parameter(Position = 1)]
    [ValidateSet('fabric', 'quilt', 'forge', 'neoforge', 'all')]
    [string]$Loader = 'all',

    [switch]$NoClean,
    [switch]$Stacktrace,
    [switch]$RefreshDependencies,
    [switch]$PrintProfile
)

$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location $Root

$ProfileFile = Join-Path $Root "buildProfiles/$Profile.properties"
if (-not (Test-Path -LiteralPath $ProfileFile -PathType Leaf)) {
    $Supported = Get-ChildItem (Join-Path $Root 'buildProfiles') -Filter '*.properties' |
        ForEach-Object { $_.BaseName } |
        Sort-Object { [version]$_ }
    throw "Unsupported Minecraft profile '$Profile'. Supported profiles: $($Supported -join ', ')"
}

if ($Loader -ne 'all') {
    $EnableKey = "enable_$Loader"
    $EnabledLine = Select-String -LiteralPath $ProfileFile -Pattern "^$EnableKey=(true|false)$" | Select-Object -Last 1
    if (-not $EnabledLine) {
        throw "Profile '$Profile' does not define $EnableKey."
    }
    if ($EnabledLine.Matches[0].Groups[1].Value -ne 'true') {
        throw "Loader '$Loader' is disabled for Minecraft $Profile in $ProfileFile."
    }
}

$Task = switch ($Loader) {
    'fabric'   { 'buildFabric' }
    'quilt'    { 'buildQuilt' }
    'forge'    { 'buildForge' }
    'neoforge' { 'buildNeoForge' }
    'all'      { 'buildAllLoaders' }
}

$GradleArgs = @(
    "-PmcProfile=$Profile",
    "-PtargetLoader=$Loader"
)

if ($PrintProfile) {
    & .\gradlew.bat "-PmcProfile=$Profile" '-PtargetLoader=none' printBuildProfile
    if ($LASTEXITCODE -ne 0) { throw "Gradle profile validation failed with exit code $LASTEXITCODE." }
}
if (-not $NoClean) { $GradleArgs += 'clean' }
$GradleArgs += $Task
if ($Stacktrace) { $GradleArgs += '--stacktrace' }
if ($RefreshDependencies) { $GradleArgs += '--refresh-dependencies' }

Write-Host "Building Minecraft $Profile for loader '$Loader' using task '$Task'."
& .\gradlew.bat @GradleArgs
if ($LASTEXITCODE -ne 0) { throw "Gradle build failed with exit code $LASTEXITCODE." }
