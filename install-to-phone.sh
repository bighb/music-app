#!/usr/bin/env bash
# One-click install and launch script for Android phone (macOS/Linux counterpart of install-to-phone.ps1)
set -u

cd "$(dirname "$0")"

CYAN='\033[36m'; YELLOW='\033[33m'; GREEN='\033[32m'; RED='\033[31m'; RESET='\033[0m'

if ! command -v adb >/dev/null 2>&1; then
    echo -e "${RED}错误: 未找到 adb，请先执行 brew install --cask android-platform-tools${RESET}"
    exit 1
fi

echo -e "${CYAN}========================================${RESET}"
echo -e "${CYAN}   原生音乐播放器 - 一键安装到手机${RESET}"
echo -e "${CYAN}========================================${RESET}"

APK_PATH="$PWD/music-app.apk"
if [ ! -f "$APK_PATH" ]; then
    echo -e "${RED}错误: 未找到 $APK_PATH，请先执行 ./gradlew assembleDebug 编译打包！${RESET}"
    exit 1
fi

echo -e "${YELLOW}[1/3] 正在检测已连接的安卓设备...${RESET}"
DEVICES=$(adb devices | awk 'NR>1 && $2=="device" {print $1}')

if [ -z "$DEVICES" ]; then
    echo -e "\n${RED}未检测到已连接的安卓手机！请按照以下步骤检查：${RESET}"
    echo "  1. 手机使用 USB 数据线连接到电脑（选择【传输文件】模式）"
    echo "  2. 手机打开【设置】->【系统/关于手机】-> 连续点击【版本号】7次开启开发者选项"
    echo "  3. 进入【开发者选项】，开启【USB 调试】和【允许通过 USB 安装应用】"
    echo "  4. 手机弹出提示【允许 USB 调试吗？】时勾选【始终允许】并点击确定"
    echo -e "\n${YELLOW}连接成功后重新运行本脚本即可！${RESET}"
    exit 1
fi

echo -e "${GREEN}已检测到设备: $(echo $DEVICES | tr ' ' ',')${RESET}"

echo -e "\n${YELLOW}[2/3] 正在传输并安装 APK 到手机中...${RESET}"
if adb install -r -d "$APK_PATH"; then
    echo -e "${GREEN}安装成功！${RESET}"
    echo -e "\n${YELLOW}[3/3] 正在自动启动【原生音乐】应用...${RESET}"
    adb shell am start -n com.personal.musicapp/.MainActivity
    echo -e "\n${CYAN}已在手机上成功启动播放器！${RESET}"
else
    echo -e "\n${RED}安装失败，请检查手机屏幕上是否有【允许安装来自此电脑的应用】安全弹窗确认。${RESET}"
    exit 1
fi
