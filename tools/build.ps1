param([switch]$Bootstrap)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot
Push-Location $root
try {
    if($Bootstrap) {
        & "$env:USERPROFILE\miniconda3\python.exe" "$PSScriptRoot\bootstrap.py"
        if($LASTEXITCODE -ne 0){throw 'Toolchain bootstrap failed'}
    }
    $env:JAVA_HOME=(Get-Content "$PSScriptRoot\jdk-path.txt" -Raw).Trim()
    $sdk="$env:USERPROFILE\.cache\pico-android\sdk"
    ('sdk.dir='+$sdk.Replace('\','/')) | Set-Content "$root\local.properties"
    $env:ANDROID_HOME=$sdk
    & "$env:USERPROFILE\.cache\pico-android\gradle-8.9\bin\gradle.bat" --no-daemon :bridge:assembleDebug :probe:assembleDebug
    if($LASTEXITCODE -ne 0){throw 'Gradle build failed'}
    New-Item -ItemType Directory -Path "$root\artifacts" -Force | Out-Null
    Copy-Item -LiteralPath "$root\bridge\build\outputs\apk\debug\bridge-debug-unsigned.apk" -Destination "$root\artifacts\PicoFacialBridge-OSC-Direct-unsigned.apk"
    Copy-Item -LiteralPath "$root\probe\build\outputs\apk\debug\probe-debug.apk" -Destination "$root\artifacts\PicoFacialBinderProbe-bare.apk"
    & "$PSScriptRoot\build-probe.ps1" -DeclareTrackingPermissions
    if($LASTEXITCODE -ne 0){throw 'Declared probe build failed'}
    Get-FileHash "$root\artifacts\PicoFacialBridge-OSC-Direct-unsigned.apk" -Algorithm SHA256
} finally { Pop-Location }
