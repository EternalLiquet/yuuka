param(
  [Parameter(Mandatory = $true)]
  [ValidatePattern('^[^@\s]+@[^@\s]+\.[^@\s]+$')]
  [string]$Email
)

$ErrorActionPreference = 'Stop'
$securePassword = Read-Host 'Owner password (12+ characters)' -AsSecureString
$passwordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
$originalLocation = Get-Location

try {
  $env:YUUKA_PASSWORD_TO_HASH = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordPointer)
  $env:YUUKA_OWNER_EMAIL = $Email
  Set-Location (Join-Path $PSScriptRoot '..\backend')

  Write-Host 'Password hash:'
  & .\gradlew.bat -q printPasswordHash
  if ($LASTEXITCODE -ne 0) { throw 'Password hash generation failed.' }

  Write-Host "`nAuthenticator enrollment:"
  & .\gradlew.bat -q printTotpSecret
  if ($LASTEXITCODE -ne 0) { throw 'TOTP generation failed.' }
}
finally {
  Set-Location $originalLocation
  Remove-Item Env:YUUKA_PASSWORD_TO_HASH -ErrorAction SilentlyContinue
  Remove-Item Env:YUUKA_OWNER_EMAIL -ErrorAction SilentlyContinue
  [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPointer)
}
