# Real-time log monitor for MusicApp
$adbPath = "C:\Users\admin\AppData\Local\Android\Sdk\platform-tools\adb.exe"
if (!(Test-Path $adbPath)) {
    $adbPath = "adb"
}

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "   原生音乐播放器 - 实时真机调试日志" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "正在监听手机日志 (按 Ctrl+C 停止)...`n" -ForegroundColor Yellow

& $adbPath logcat -c
& $adbPath logcat -v time | ForEach-Object {
    if ($_ -match "com.personal.musicapp|MusicApp|ExoPlayer|MediaSession|Douyin|NetMusic") {
        if ($_ -match " E/|Error|Exception|FAIL") {
            Write-Host $_ -ForegroundColor Red
        } elseif ($_ -match " W/|Warn") {
            Write-Host $_ -ForegroundColor Yellow
        } else {
            Write-Host $_ -ForegroundColor Green
        }
    }
}
