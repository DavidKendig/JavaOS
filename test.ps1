# Compiles and runs the JavaOS checks.
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
if (-not (Test-Path (Join-Path $root "out\javaos\Boot.class"))) {
    & (Join-Path $root "build.ps1") | Out-Null
}
$outTest = Join-Path $root "out-test"
New-Item -ItemType Directory -Force $outTest | Out-Null
& javac -cp (Join-Path $root "out") -d $outTest (Get-ChildItem (Join-Path $root "test") -Filter *.java | ForEach-Object { $_.FullName })
if ($LASTEXITCODE -ne 0) { throw "test compilation failed" }

$failed = $false
foreach ($suite in @("CoreTest", "OfficeInteropTest")) {
    Write-Host "=== $suite ==="
    & java -cp "$(Join-Path $root 'out');$outTest" $suite
    if ($LASTEXITCODE -ne 0) { $failed = $true }
    Write-Host ""
}
if ($failed) { exit 1 }
