#!/usr/bin/env bash
# Real-time log monitor for MusicApp (macOS/Linux counterpart of view-phone-logcat.ps1)
set -u

YELLOW='\033[33m'; GREEN='\033[32m'; RED='\033[31m'; CYAN='\033[36m'; RESET='\033[0m'

if ! command -v adb >/dev/null 2>&1; then
    echo -e "${RED}错误: 未找到 adb，请先执行 brew install --cask android-platform-tools${RESET}"
    exit 1
fi

echo -e "${CYAN}========================================${RESET}"
echo -e "${CYAN}   原生音乐播放器 - 实时真机调试日志${RESET}"
echo -e "${CYAN}========================================${RESET}"
echo -e "${YELLOW}正在监听手机日志 (按 Ctrl+C 停止)...${RESET}\n"

FILTER='com.personal.musicapp|MusicApp|MusicPlayerManager|PlaybackService|MainScreen|Kuwo|ExoPlayer|MediaSession|Douyin|NetMusic'
ERR_RE=' E/|Error|Exception|FAIL'
WARN_RE=' W/|Warn'

adb logcat -c
adb logcat -v time | grep --line-buffered -E "$FILTER" | while IFS= read -r line; do
    if [[ "$line" =~ $ERR_RE ]]; then
        echo -e "${RED}${line}${RESET}"
    elif [[ "$line" =~ $WARN_RE ]]; then
        echo -e "${YELLOW}${line}${RESET}"
    else
        echo -e "${GREEN}${line}${RESET}"
    fi
done
