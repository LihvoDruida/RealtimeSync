[CmdletBinding()]
param(
    [ValidateSet('fabric', 'quilt', 'forge', 'neoforge', 'all')]
    [string]$Loader = 'all',

    [string[]]$Profile,

    [ValidatePattern('^[0-9A-Za-z][0-9A-Za-z._+-]*$')]
    [string]$ModVersion = '',

    [switch]$Stacktrace,
    [switch]$RefreshDependencies
)

$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location $Root

if (-not $Profile -or $Profile.Count -eq 0) {
    $Profile = Get-ChildItem (Join-Path $Root 'buildProfiles') -Filter '*.properties' |
        ForEach-Object { $_.BaseName } |
        Sort-Object { [version]$_ }
}

foreach ($CurrentProfile in $Profile) {
    Write-Host "==> Building Minecraft profile $CurrentProfile ($Loader)"
    $BuildArguments = @{
        Profile = $CurrentProfile
        Loader = $Loader
        Stacktrace = $Stacktrace
        RefreshDependencies = $RefreshDependencies
    }
    if ($ModVersion) { $BuildArguments.ModVersion = $ModVersion }
    & (Join-Path $PSScriptRoot 'build.ps1') @BuildArguments
}
