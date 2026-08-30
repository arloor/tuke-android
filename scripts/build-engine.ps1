param()
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
$EngineDir = Join-Path $Root "engine"
$OutRoot = Join-Path $Root "app\src\main\jniLibs"
if (-not (Get-Command go -ErrorAction SilentlyContinue)) {
    Write-Host "go not found; skip engine build"
    exit 0
}
$AndroidSdk = if ($env:ANDROID_HOME) {
    $env:ANDROID_HOME
} elseif ($env:ANDROID_SDK_ROOT) {
    $env:ANDROID_SDK_ROOT
} else {
    Join-Path $env:LOCALAPPDATA "Android\Sdk"
}
$NdkRoot = Get-ChildItem (Join-Path $AndroidSdk "ndk") -Directory -ErrorAction SilentlyContinue |
    Sort-Object Name -Descending |
    Select-Object -First 1 -ExpandProperty FullName
if (-not $NdkRoot) {
    throw "Android NDK not found under $AndroidSdk\ndk"
}
$Toolchain = Join-Path $NdkRoot "toolchains\llvm\prebuilt\windows-x86_64\bin"
$abis = @(
    @{ goarch = "arm64"; jni = "arm64-v8a"; triple = "aarch64-linux-android" },
    @{ goarch = "amd64"; jni = "x86_64"; triple = "x86_64-linux-android" }
)
foreach ($abi in $abis) {
    $destDir = Join-Path $OutRoot $abi.jni
    New-Item -ItemType Directory -Path $destDir -Force | Out-Null
    $dest = Join-Path $destDir "libtuke.so"
    Write-Host "building $($abi.jni) -> $dest"
    $env:CGO_ENABLED = "1"
    $env:GOOS = "android"
    $env:GOARCH = $abi.goarch
    $env:CC = Join-Path $Toolchain "$($abi.triple)26-clang.cmd"
    Push-Location $EngineDir
    try {
        go build -trimpath -ldflags "-s -w -buildid=" -o $dest .
        if ($LASTEXITCODE -ne 0) {
            throw "engine build failed for $($abi.jni)"
        }
    } finally {
        Pop-Location
    }
}
Write-Host "engine binaries ready"
