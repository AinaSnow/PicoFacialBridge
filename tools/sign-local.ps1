param(
    [Parameter(Mandatory=$true)][string]$InputApk,
    [Parameter(Mandatory=$true)][string]$OutputApk,
    [Parameter(Mandatory=$true)][string]$ApkSignerJar,
    [Parameter(Mandatory=$true)][string]$ApkSignerSha256,
    [string]$SigningDirectory = (Join-Path $env:USERPROFILE '.pico-facialbridge-signing')
)
$ErrorActionPreference = 'Stop'
$keyFile = Join-Path $SigningDirectory 'osc-direct.p12'
$passwordFile = Join-Path $SigningDirectory 'password.dpapi'
foreach ($file in @($keyFile,$passwordFile,$InputApk,$ApkSignerJar)) {
    if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { throw "Required file missing: $file. No key generation or fallback is allowed." }
}
$expected = (Get-Content -LiteralPath (Join-Path $PSScriptRoot 'signing-certificate.sha256') -Raw).Trim()
if ($expected -notmatch '^[a-f0-9]{64}$') { throw 'Invalid pinned signing certificate.' }
if ($ApkSignerSha256 -notmatch '^[a-fA-F0-9]{64}$' -or (Get-FileHash -LiteralPath $ApkSignerJar -Algorithm SHA256).Hash -ne $ApkSignerSha256) { throw 'Official signing tool checksum mismatch.' }
$out = [System.IO.Path]::GetFullPath($OutputApk)
$pending = $out + '.pending'
if ((Test-Path -LiteralPath $out) -or (Test-Path -LiteralPath $pending)) { throw 'Output already exists; will not overwrite.' }
$certTemp = Join-Path ([System.IO.Path]::GetTempPath()) ('pico-sign-public-' + [guid]::NewGuid() + '.der')
$secure = $null
try {
    $secure = Get-Content -LiteralPath $passwordFile -Raw | ConvertTo-SecureString
    $ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try { $env:PICO_SIGN_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr) }
    & keytool -exportcert -keystore $keyFile -storetype PKCS12 -storepass:env PICO_SIGN_PASSWORD -alias osc-direct -file $certTemp
    if ($LASTEXITCODE -ne 0) { throw 'Cannot read signing certificate.' }
    if ((Get-FileHash -LiteralPath $certTemp -Algorithm SHA256).Hash -ne $expected) { throw 'Existing key does not match pinned certificate; refusing to sign.' }
    & java -jar $ApkSignerJar sign --ks $keyFile --ks-type PKCS12 --ks-key-alias osc-direct --ks-pass env:PICO_SIGN_PASSWORD --key-pass env:PICO_SIGN_PASSWORD --v4-signing-enabled false --out $pending $InputApk
    if ($LASTEXITCODE -ne 0) { throw 'APK signing failed.' }
    $report = & java -jar $ApkSignerJar verify --verbose --print-certs $pending
    if ($LASTEXITCODE -ne 0) { throw 'Signed APK failed signature verification.' }
    $digests = @($report | Select-String '^Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]{64})$')
    if ($digests.Count -ne 1 -or $digests[0].Matches[0].Groups[1].Value -ne $expected) { throw 'Final APK signer mismatch.' }
    Move-Item -LiteralPath $pending -Destination $out
    $report | Set-Content -LiteralPath ($out + '.signature.txt') -Encoding utf8
    Write-Output ('Verified signer: ' + $expected)
    Get-FileHash -LiteralPath $out -Algorithm SHA256
} finally {
    Remove-Item Env:\PICO_SIGN_PASSWORD -ErrorAction SilentlyContinue
    if ($secure) { $secure.Dispose() }
    if (Test-Path -LiteralPath $certTemp) { Remove-Item -LiteralPath $certTemp }
}
