# 安卓真机测试：装游戏 APK → 把 combine.jar 推进行游戏的 mods 目录 → 启动游戏。
#
#   verify/android-install.ps1                 # 自动找 Downloads 里的 [Android]*Mindustry*.apk* + build\libs\combine.jar
#   verify/android-install.ps1 -NoInstall      # 只推模组（已经装过游戏）
#   verify/android-install.ps1 -Apk <路径> -ModJar <路径>
#
# 【为什么不是"把模组塞进 APK 里再打包"】
#   Mindustry 只在**应用的 mods 目录**里扫模组（`Mods.load()` 读 `Vars.modDirectory`；
#   160.1 的 classes.dex 里没有任何 "assets/mods" / "files/mods" 之类的内置路径），
#   塞进 APK 的 assets 不会被加载 —— 那样打出来的包只是个"没带模组的原版 APK"。
#   正确做法就是本脚本干的：装原版 APK + 把 jar 推进 mods 目录（等价于游戏里的"模组 → 导入"）。
param(
  [string]$Apk = '',
  [string]$ModJar = '',
  [string]$Package = 'io.anuke.mindustry',
  [switch]$NoInstall,
  [switch]$NoLaunch
)

$ErrorActionPreference = 'Stop'
$ROOT = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path

# ---- adb ----
$adb = (Get-Command adb.exe -ErrorAction SilentlyContinue).Source
if (-not $adb) {
  $cand = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
  if (Test-Path $cand) { $adb = $cand }
}
if (-not $adb) { throw '找不到 adb（装 Android platform-tools，或把它加进 PATH）' }
Write-Host "[android] adb = $adb"

# ---- 游戏 APK ----
if (-not $Apk) {
  $Apk = (Get-ChildItem (Join-Path $env:USERPROFILE 'Downloads') -Filter '*Mindustry*.apk*' -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName
}
if (-not $NoInstall) {
  # 【必须 -LiteralPath】文件名里有 [ ]，Test-Path 默认按通配符解释 → 明明存在也会判 False
  if (-not $Apk -or -not (Test-Path -LiteralPath $Apk)) { throw "找不到游戏 APK（-Apk 指定，或放到 Downloads）" }
  Write-Host "[android] 游戏 APK = $Apk"
}

# ---- 模组 jar（必须是带 classes.dex 的安卓包） ----
if (-not $ModJar) { $ModJar = Join-Path $ROOT 'build\libs\combine.jar' }
if (-not (Test-Path -LiteralPath $ModJar)) { throw "找不到模组包 $ModJar —— 先跑 verify/deliver.ps1" }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::OpenRead($ModJar)
try {
  if (-not ($zip.Entries | Where-Object { $_.FullName -eq 'classes.dex' })) {
    throw "$ModJar 里没有 classes.dex —— 安卓端装不上，先跑 verify/deliver.ps1（它用 gradlew deploy 编译）"
  }
} finally { $zip.Dispose() }
Write-Host "[android] 模组 = $ModJar"

# ---- 设备 ----
$devices = @(& $adb devices | Select-String -Pattern "^\S+\s+device$")
if ($devices.Count -eq 0) {
  Write-Host ''
  Write-Host '[android] 没看到已连接的设备。两条路：' -ForegroundColor Yellow
  Write-Host '  1) 手机开「开发者选项 → USB 调试」，插上 USB，允许这台电脑调试，再跑本脚本；'
  Write-Host '  2) 不用电脑：把 Desktop\combine.jar 传到手机，游戏里「模组 → 导入」选它，重启游戏即可。'
  Write-Host '     （游戏的 mods 目录：/sdcard/Android/data/io.anuke.mindustry/files/mods）'
  exit 2
}
Write-Host ("[android] 设备 = " + ($devices -join ', '))

if (-not $NoInstall) {
  Write-Host '[android] 1/3 安装游戏 APK（-r 覆盖安装，保留数据）'
  & $adb install -r $Apk
  if ($LASTEXITCODE -ne 0) { throw 'adb install 失败（手机要允许"安装未知来源"，或先卸载签名不同的旧版）' }
} else {
  Write-Host '[android] 1/3 跳过安装（-NoInstall）'
}

Write-Host '[android] 2/3 把模组推进游戏 mods 目录'
$modsDir = "/sdcard/Android/data/$Package/files/mods"
& $adb shell mkdir -p $modsDir
& $adb shell am force-stop $Package   # 先停游戏：模组列表只在启动时扫
& $adb push $ModJar "$modsDir/combine.jar"
if ($LASTEXITCODE -ne 0) { throw "adb push 失败：$modsDir（目录权限/路径不对时，改用游戏里的「模组 → 导入」）" }
& $adb shell ls -l "$modsDir"

if (-not $NoLaunch) {
  Write-Host '[android] 3/3 启动游戏'
  & $adb shell am start -n "$Package/mindustry.android.AndroidLauncher" | Out-Null
}

Write-Host ''
Write-Host '[android] 好了：进游戏「模组」里应能看到 combine。'
Write-Host '         手机上要看的：① 矿物阴影框选提示是"单指拖框"；② 阴影上点/拖钻头 → 按 ✓ 出 1x1 钻头；'
Write-Host '                       ③ 同种传送带点一下/拖一排 → 按 ✓ 叠层（不用再手动对齐方向）。'
