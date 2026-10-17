$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot
$java=(Get-Content "$root\tools\jdk-path.txt" -Raw).Trim()
$out="$root\build\independent-tests"
New-Item -ItemType Directory -Path $out -Force | Out-Null
$sources=@("$PSScriptRoot\IndependentExpressionTest.java","$PSScriptRoot\CadenceTest.java")
foreach($name in @('TrackingBuffer','TrackingData','FramePump','DeliveryLedger','XDeliveryStats')) {
    $file="$root\shared\src\main\java\dev\pico\facialprobe\$name.java"
    if(Test-Path $file){$sources+=$file}
}
& "$java\bin\javac.exe" -encoding UTF-8 -d $out @sources
if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}
& "$java\bin\java.exe" -cp $out dev.pico.facialprobe.IndependentExpressionTest
exit $LASTEXITCODE
