# 在**本机**起一个安卓模拟器来测这个模组（不用 Google 账号、不往系统里装东西：
# 系统镜像从腾讯云镜像下到 **项目内** build\android\，AVD 也在项目内，删 build\ 就干净了）。
#
#   verify/android-emulator.ps1                 # 一条龙：确保镜像/AVD → 起模拟器 → 装游戏 → 推模组 → 启动
#   verify/android-emulator.ps1 -OnlyBoot       # 只把模拟器起来
#   verify/android-emulator.ps1 -Stop           # 关掉模拟器
#   verify/android-emulator.ps1 -Shot a.png     # 截屏到文件（项目内相对路径或绝对路径都行）
#   verify/android-emulator.ps1 -Tap 267,895    # 点一下（坐标就是截图里的像素坐标，可重复 -Tap）
#   verify/android-emulator.ps1 -Swipe 100,600,500,600   # 划一下（x1,y1,x2,y2）
#
# 环境细节（踩过的坑）：
#  · dl.google.com 在这台机器上 DNS 不通 → 用 mirrors.cloud.tencent.com/AndroidSDK 下系统镜像（564MB，API 28 x86_64）
#  · 模拟器只认 SDK 布局：镜像放到 <sdk>\system-images\android-28\default\x86_64\，
#    这里用 junction 把项目内 build\android\sdk\{emulator,platform-tools} 指到真 SDK，镜像指到项目内解包目录
#  · AVD 用 ANDROID_AVD_HOME 指到项目内；-no-window 起（截图走 adb screencap，照样能看到画面）
param(
  [string]$Apk = '',
  [string]$ModJar = '',
  [switch]$OnlyBoot,
  [switch]$Stop,
  [string]$Shot = '',
  [string]$Tap = '',      # "x,y" 或 "x1,y1,x2,y2"（多个点连着写；用字符串是因为 -File 传数组会被当成一个字符串）
  [string]$Swipe = '',
  [switch]$KeepAppData,
  [int]$BootTimeoutSec = 300
)

$ErrorActionPreference = 'Stop'
$ROOT = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$BASE = Join-Path $ROOT 'build\android'
$SDK = Join-Path $BASE 'sdk'
$AVD_HOME = Join-Path $BASE 'avd'
$SYSIMG = Join-Path $BASE 'sysimg\x86_64'
$AVD_NAME = 'c28'
$PKG = 'io.anuke.mindustry'
$IMG_URL = 'https://mirrors.cloud.tencent.com/AndroidSDK/sys-img/android/x86_64-28_r04.zip'

$adb = Join-Path $SDK 'platform-tools\adb.exe'
$emu = Join-Path $SDK 'emulator\emulator.exe'

function Ensure-Layout() {
  New-Item -ItemType Directory -Force -Path (Join-Path $SDK 'system-images\android-28\default'), $AVD_HOME,
    (Join-Path $AVD_HOME "$AVD_NAME.avd"), $BASE | Out-Null
  $realSdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
  foreach ($j in @(@((Join-Path $SDK 'emulator'), (Join-Path $realSdk 'emulator')),
      @((Join-Path $SDK 'platform-tools'), (Join-Path $realSdk 'platform-tools')),
      @((Join-Path $SDK 'system-images\android-28\default\x86_64'), $SYSIMG))) {
    if (-not (Test-Path -LiteralPath $j[0])) { New-Item -ItemType Junction -Path $j[0] -Target $j[1] | Out-Null }
  }
}

function Ensure-Image() {
  if (Test-Path -LiteralPath (Join-Path $SYSIMG 'system.img')) { return }
  Write-Host "[emu] 下系统镜像（API 28 x86_64，约 540MB）…"
  $zip = Join-Path $BASE 'x86_64-28_r04.zip'
  if (-not (Test-Path -LiteralPath $zip)) {
    & curl.exe -sS -L --ssl-no-revoke --max-time 3600 -o $zip $IMG_URL
    if ($LASTEXITCODE -ne 0) { throw "下载系统镜像失败（$IMG_URL）" }
  }
  Write-Host '[emu] 解包…'
  Expand-Archive -LiteralPath $zip -DestinationPath (Join-Path $BASE 'sysimg') -Force
  Ensure-Layout
}

function Ensure-Avd() {
  $ini = Join-Path $AVD_HOME "$AVD_NAME.ini"
  if (-not (Test-Path -LiteralPath $ini)) {
    Set-Content -LiteralPath $ini -Encoding ASCII -Value @("avd.ini.encoding=UTF-8",
      "path=$(Join-Path $AVD_HOME "$AVD_NAME.avd")", "path.rel=avd\$AVD_NAME.avd", 'target=android-28')
  }
  $cfg = Join-Path $AVD_HOME "$AVD_NAME.avd\config.ini"
  if (-not (Test-Path -LiteralPath $cfg)) {
    Set-Content -LiteralPath $cfg -Encoding ASCII -Value @(
      "AvdId=$AVD_NAME", "avd.ini.displayname=$AVD_NAME", 'avd.ini.encoding=UTF-8',
      'abi.type=x86_64', 'hw.cpu.arch=x86_64', 'hw.cpu.ncore=4', 'hw.ramSize=2048', 'vm.heapSize=256',
      'hw.lcd.width=720', 'hw.lcd.height=1280', 'hw.lcd.density=320',
      'hw.gpu.enabled=yes', 'hw.gpu.mode=swiftshader_indirect',
      'hw.keyboard=yes', 'hw.dPad=no', 'hw.mainKeys=no', 'disk.dataPartition.size=4096M',
      'image.sysdir.1=system-images\android-28\default\x86_64\',
      'tag.id=default', 'tag.display=Default Android System Image',
      'skin.path=_no_skin', 'skin.name=720x1280', 'showDeviceFrame=no')
  }
}

function Emu-Env() {
  $env:ANDROID_AVD_HOME = $AVD_HOME
  $env:ANDROID_SDK_ROOT = $SDK
  $env:ANDROID_HOME = $SDK
  $env:ANDROID_SDK_HOME = $BASE
  $env:ANDROID_USER_HOME = $BASE
}

function Booted() {
  try { return ("$(& $adb shell getprop sys.boot_completed 2>$null)" -replace '\s', '') -eq '1' } catch { return $false }
}

Ensure-Layout

if ($Stop) {
  & $adb emu kill 2>&1 | Out-Null
  Write-Host '[emu] 已关闭'
  exit 0
}

$tapList = @()
if ($Tap) { $tapList = @($Tap -split ',' | ForEach-Object { [int]$_.Trim() }) }
$swipeList = @()
if ($Swipe) { $swipeList = @($Swipe -split ',' | ForEach-Object { [int]$_.Trim() }) }

if (-not $Shot -and $tapList.Count -eq 0 -and $swipeList.Count -eq 0) {
  Ensure-Image
  Ensure-Avd
  Emu-Env
  if (-not (Booted)) {
    Write-Host '[emu] 启动模拟器（-no-window，软件 GL）…'
    Start-Process -FilePath $emu -ArgumentList @('-avd', $AVD_NAME, '-no-window', '-no-audio', '-no-boot-anim',
        '-no-snapshot', '-gpu', 'swiftshader_indirect') -WindowStyle Hidden `
      -RedirectStandardOutput (Join-Path $BASE 'emulator.log') -RedirectStandardError (Join-Path $BASE 'emulator.err.log')
    $deadline = (Get-Date).AddSeconds($BootTimeoutSec)
    while (-not (Booted)) {
      if ((Get-Date) -gt $deadline) { throw "模拟器 $BootTimeoutSec 秒还没开机完成（看 build\android\emulator.err.log）" }
      Start-Sleep -Seconds 5
    }
  }
  Write-Host "[emu] 已开机：$((& $adb devices | Select-String 'emulator').Line)"
  if ($OnlyBoot) { exit 0 }

  # ---- 装游戏 + 推模组 ----
  if (-not $Apk) {
    $Apk = (Get-ChildItem (Join-Path $env:USERPROFILE 'Downloads') -File -ErrorAction SilentlyContinue |
      Where-Object { $_.Name -like '*Mindustry*.apk*' } | Sort-Object LastWriteTime -Descending |
      Select-Object -First 1).FullName
  }
  if (-not $Apk -or -not (Test-Path -LiteralPath $Apk)) { throw '找不到游戏 APK（-Apk 指定）' }
  $apkLocal = Join-Path $BASE 'mindustry.apk'
  Copy-Item -LiteralPath $Apk -Destination $apkLocal -Force   # adb install 只认 .apk 结尾
  Write-Host '[emu] 安装游戏 APK…'
  & $adb install -r $apkLocal | Select-Object -Last 1

  if (-not $ModJar) { $ModJar = Join-Path $ROOT 'build\libs\combine.jar' }
  if (-not (Test-Path -LiteralPath $ModJar)) { throw "找不到模组包 $ModJar —— 先跑 verify/deliver.ps1" }
  & $adb shell am force-stop $PKG
  & $adb shell mkdir -p "/sdcard/Android/data/$PKG/files/mods"
  & $adb push $ModJar "/sdcard/Android/data/$PKG/files/mods/combine.jar" | Select-Object -Last 1
  Write-Host '[emu] 启动游戏'
  & $adb logcat -c
  & $adb shell am start -n "$PKG/mindustry.android.AndroidLauncher" | Out-Null
  Write-Host '[emu] 好了：等画面出来调 -Tap / -Shot（坐标就是截图里的像素）'
  exit 0
}

# ---- 单步操作 ----
if ($tapList.Count -ge 2) {
  for ($i = 0; $i + 1 -lt $tapList.Count; $i += 2) {
    Write-Host "[emu] tap $($tapList[$i]) $($tapList[$i + 1])"
    & $adb shell input tap $tapList[$i] $tapList[$i + 1]
    Start-Sleep -Milliseconds 800
  }
}
if ($swipeList.Count -ge 4) {
  Write-Host "[emu] swipe $($swipeList -join ',')"
  & $adb shell input swipe $swipeList[0] $swipeList[1] $swipeList[2] $swipeList[3] 400
  Start-Sleep -Milliseconds 800
}
if ($Shot) {
  $out = if ([IO.Path]::IsPathRooted($Shot)) { $Shot } else { Join-Path $ROOT $Shot }
  & $adb shell screencap -p /sdcard/_shot.png
  & $adb pull /sdcard/_shot.png $out | Select-Object -Last 1
  Write-Host "[emu] 截图 → $out"
}
