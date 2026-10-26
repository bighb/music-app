# One-click install and launch script for Android phone
$adbPath = "C:\Users\admin\AppData\Local\Android\Sdk\platform-tools\adb.exe"
if (!(Test-Path $adbPath)) {
    $adbPath = "adb"
}

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "   原生音乐播放器 - 一键安装到手机" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

$apkPath = "$PSScriptRoot\music-app.apk"
if (!(Test-Path $apkPath)) {
    Write-Host "错误: 未找到 $apkPath，请先执行 .\gradlew.bat assembleDebug 编译打包！" -ForegroundColor Red
    exit 1
}

Write-Host "[1/3] 正在检测已连接的安卓设备..." -ForegroundColor Yellow
$devices = & $adbPath devices | Where-Object { $_ -match "\bdevice\b" -and $_ -notmatch "List of devices" }

if (!$devices) {
    Write-Host "`n未检测到已连接的安卓手机！请按照以下步骤检查：" -ForegroundColor Red
    Write-Host "  1. 手机使用 USB 数据线连接到电脑（选择【传输文件】模式）" -ForegroundColor White
    Write-Host "  2. 手机打开【设置】->【系统/关于手机】-> 连续点击【版本号】7次开启开发者选项" -ForegroundColor White
    Write-Host "  3. 进入【开发者选项】，开启【USB 调试】和【允许通过 USB 安装应用】" -ForegroundColor White
    Write-Host "  4. 手机弹出提示【允许 USB 调试吗？】时勾选【始终允许】并点击确定" -ForegroundColor White
    Write-Host "`n连接成功后重新运行本脚本即可！" -ForegroundColor Yellow
    exit 1
}

Write-Host "已检测到设备: $($devices -join ', ')" -ForegroundColor Green

Write-Host "`n[2/3] 正在传输并安装 APK 到手机中..." -ForegroundColor Yellow
& $adbPath install -r -d $apkPath

if ($LASTEXITCODE -eq 0) {
    Write-Host "安装成功！" -ForegroundColor Green
    Write-Host "`n[3/3] 正在自动启动【原生音乐】应用..." -ForegroundColor Yellow
    & $adbPath shell am start -n com.personal.musicapp/.MainActivity
    Write-Host "`n已在手机上成功启动播放器！" -ForegroundColor Cyan
} else {
    Write-Host "`n安装失败，请检查手机屏幕上是否有【允许安装来自此电脑的应用】安全弹窗确认。" -ForegroundColor Red
}
