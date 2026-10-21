$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot
$out = Join-Path $root 'build/host-tests'
New-Item -ItemType Directory -Force $out | Out-Null
$source = Join-Path $root 'shared/src/main/java/dev/pico/facialprobe'
& javac --release 8 -d $out "$source/DirectFaceMapper.java" "$source/BridgeDiagnostics.java" "$source/OscFacePacket.java" "$source/OscDestination.java" "$PSScriptRoot/DirectOscTest.java" "$PSScriptRoot/BridgeDiagnosticsTest.java"
if ($LASTEXITCODE -ne 0) { throw 'Compilation failed' }
& java -cp $out dev.pico.facialprobe.DirectOscTest
if ($LASTEXITCODE -ne 0) { throw 'Direct OSC tests failed' }

& java -cp $out dev.pico.facialprobe.BridgeDiagnosticsTest
if ($LASTEXITCODE -ne 0) { throw "Diagnostics tests failed" }
