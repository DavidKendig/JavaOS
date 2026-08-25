# Compiles if needed, then starts the desktop.
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
if (-not (Test-Path (Join-Path $root "out\javaos\Boot.class"))) {
    & (Join-Path $root "build.ps1")
}
& java -cp (Join-Path $root "out") javaos.Boot @args
