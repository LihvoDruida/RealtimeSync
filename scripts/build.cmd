@echo off
setlocal EnableExtensions

set "ROOT=%~dp0.."
pushd "%ROOT%" >NUL || exit /b 2

set "PROFILE=%~1"
set "LOADER=%~2"
set "MOD_VERSION=%~3"
if "%PROFILE%"=="" set "PROFILE=1.21.5"
if "%LOADER%"=="" set "LOADER=all"

set "TASK="
if /I "%LOADER%"=="fabric" set "TASK=buildFabric"
if /I "%LOADER%"=="quilt" set "TASK=buildQuilt"
if /I "%LOADER%"=="forge" set "TASK=buildForge"
if /I "%LOADER%"=="neoforge" set "TASK=buildNeoForge"
if /I "%LOADER%"=="all" set "TASK=buildAllLoaders"

if "%TASK%"=="" (
  echo ERROR: Unsupported loader "%LOADER%". Use fabric, quilt, forge, neoforge or all. 1>&2
  popd
  exit /b 2
)
if not exist "buildProfiles\%PROFILE%.properties" (
  echo ERROR: Missing buildProfiles\%PROFILE%.properties. 1>&2
  popd
  exit /b 2
)

if "%MOD_VERSION%"=="" (
  call gradlew.bat "-PmcProfile=%PROFILE%" "-PtargetLoader=%LOADER%" clean %TASK%
) else (
  call gradlew.bat "-PmcProfile=%PROFILE%" "-PtargetLoader=%LOADER%" "-PmodVersion=%MOD_VERSION%" clean %TASK%
)
set "EXIT_CODE=%ERRORLEVEL%"
popd
exit /b %EXIT_CODE%
