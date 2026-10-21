$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot
$missing = Join-Path $root ('build/absent-signing-' + [guid]::NewGuid())
$output = Join-Path $root ('build/must-not-sign-' + [guid]::NewGuid() + '.apk')
try {
    & (Join-Path $root 'tools/sign-local.ps1') -SigningDirectory $missing -InputApk 'absent-input.apk' -OutputApk $output -ApkSignerJar 'absent-tool.jar' -ApkSignerSha256 ('0' * 64)
    throw 'Unexpected signing success without a key.'
} catch {
    if ($_.Exception.Message -notlike '*No key generation or fallback is allowed*') { throw }
}
if ((Test-Path -LiteralPath $missing) -or (Test-Path -LiteralPath $output)) { throw 'Missing key created signing state or APK.' }
Write-Output 'PASS: missing signing key fails without creating a key, output, or fallback identity'
