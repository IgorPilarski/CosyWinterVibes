$ErrorActionPreference = "Stop"

$jdk25 = Get-ChildItem "C:\Program Files\Eclipse Adoptium" -Directory -Filter "jdk-25*" -ErrorAction SilentlyContinue |
    Select-Object -First 1 -ExpandProperty FullName

$jdkCandidates = @(
    $jdk25,
    "C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot",
    (Get-ChildItem "C:\Program Files\Eclipse Adoptium" -Directory -Filter "jdk-21*" -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty FullName)
)

$jdk = $jdkCandidates | Where-Object { $_ -and (Test-Path "$_\bin\java.exe") } | Select-Object -First 1

if (-not $jdk) {
    Write-Host "JDK 25/21 not found. Install Eclipse Temurin or set JAVA_HOME." -ForegroundColor Red
    exit 1
}

$env:JAVA_HOME = $jdk
Write-Host "JAVA_HOME=$env:JAVA_HOME" -ForegroundColor Green

& "$PSScriptRoot\mvnw.cmd" @args
exit $LASTEXITCODE
