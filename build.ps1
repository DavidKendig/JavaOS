# Builds JavaOS into out\ and packages javaos.jar.
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$out = Join-Path $root "out"

# The Oracle javapath shim on PATH exposes java and javac but not jar, so fall
# back to the bin directory of whichever JDK is actually running.
function Resolve-JdkTool([string]$name) {
    $found = Get-Command $name -ErrorAction SilentlyContinue
    if ($found) { return $found.Source }
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin\$name.exe"))) {
        return (Join-Path $env:JAVA_HOME "bin\$name.exe")
    }
    $properties = cmd /c "java -XshowSettings:properties -version 2>&1"
    $line = $properties | Select-String "java\.home"
    if ($line) {
        $javaHome = ($line -split "=", 2)[1].Trim()
        $candidate = Join-Path $javaHome "bin\$name.exe"
        if (Test-Path $candidate) { return $candidate }
    }
    return $null
}

if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Force $out | Out-Null

$sources = Get-ChildItem -Path (Join-Path $root "src") -Recurse -Filter *.java |
    ForEach-Object { $_.FullName }
Write-Host "Compiling $($sources.Count) source files..."
& javac -d $out $sources
if ($LASTEXITCODE -ne 0) { throw "compilation failed" }

$jar = Resolve-JdkTool "jar"
if (-not $jar) {
    Write-Host "Compiled to $out. (jar was not found, so no jar was packaged.)"
    Write-Host "Run it with:  .\run.ps1"
    return
}

& $jar --create --file (Join-Path $root "javaos.jar") --main-class javaos.Boot -C $out .
if ($LASTEXITCODE -ne 0) { throw "packaging failed" }

Write-Host "Built javaos.jar. Run it with:  java -jar javaos.jar"
