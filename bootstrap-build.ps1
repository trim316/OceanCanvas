$ErrorActionPreference = 'Stop'

$GradleVersion = '9.5.1'
$BootstrapRoot = Join-Path $PSScriptRoot '.gradle-bootstrap'
$GradleHome = Join-Path $BootstrapRoot "gradle-$GradleVersion"
$GradleBat = Join-Path $GradleHome 'bin\gradle.bat'

if (-not $env:JAVA_HOME) {
    throw 'JAVA_HOME is not set. Ocean Canvas 26.2 requires JDK 25.'
}

$JavaVersion = & (Join-Path $env:JAVA_HOME 'bin\java.exe') -version 2>&1
if ($LASTEXITCODE -ne 0 -or ($JavaVersion -join "`n") -notmatch 'version "25') {
    throw "JAVA_HOME must point to JDK 25. Current output:`n$($JavaVersion -join "`n")"
}

if (-not (Test-Path $GradleBat)) {
    New-Item -ItemType Directory -Force -Path $BootstrapRoot | Out-Null
    $ZipPath = Join-Path $BootstrapRoot "gradle-$GradleVersion-bin.zip"
    $Uri = "https://services.gradle.org/distributions/gradle-$GradleVersion-bin.zip"

    Write-Host "Downloading Gradle $GradleVersion..."
    Invoke-WebRequest -Uri $Uri -OutFile $ZipPath
    Expand-Archive -Path $ZipPath -DestinationPath $BootstrapRoot -Force
    Remove-Item $ZipPath
}

Write-Host 'Building Ocean Canvas...'
& $GradleBat --no-daemon clean build
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

Write-Host "Build complete. See $PSScriptRoot\build\libs"
