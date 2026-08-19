@echo off
setlocal enabledelayedexpansion
REM Builds a jar for every supported Minecraft version.
REM Windows twin of build-all.sh -- keep the two in sync.
REM
REM Each target gets its own build directory (build\<mcVersion>), so this does not thrash
REM Loom's caches. Finished jars are collected into dist\.
REM
REM Do NOT pipe this script's output into another command: the pipe's exit code would mask
REM a real build failure.

cd /d "%~dp0"

set "VERSIONS=1.21.4 1.21.8 1.21.11"

REM The wrapper is the default. Override with a local distribution if the wrapper download
REM turns flaky again, e.g.:
REM     set "GRADLE_CMD=D:\Minecraft\ce\gradle-9.5.1\bin\gradle.bat"
if not defined GRADLE_CMD set "GRADLE_CMD=%~dp0gradlew.bat"

if exist dist rmdir /s /q dist
mkdir dist

for %%v in (%VERSIONS%) do (
    echo.
    echo ==============================================
    echo   Minecraft %%v
    echo ==============================================
    REM Remove artifacts from older version bumps before collecting this build.
    if exist "build\%%v\libs\*.jar" del /q "build\%%v\libs\*.jar"
    call "%GRADLE_CMD%" --console=plain -PmcVersion=%%v build
    if errorlevel 1 (
        echo.
        echo   BUILD FAILED: Minecraft %%v
        exit /b 1
    )
    copy /y "build\%%v\libs\cestats-mc%%v-*.jar" dist\ >nul
    if errorlevel 1 (
        echo.
        echo   No jar produced for Minecraft %%v
        exit /b 1
    )
)

echo.
echo ==============================================
echo   dist\
echo ==============================================
for %%f in (dist\*.jar) do (
    echo %%~nxf  ^(%%~zf bytes^)
)

exit /b 0
