#!/usr/bin/env bash
# 真机跑模拟导航（只在 debug 包上可用）
#
# 为什么需要它：GuidanceActivity 在主清单里是 exported="false"（导航页不该被外部拉起），
# 正式版没法用 adb 直达，只能手动点进 App 走「导航 Tab → 长按地图 → 导航到这里 → 选路」，
# 改一行代码就要重复一次。debug 包通过 app/src/debug/AndroidManifest.xml 里的
# activity-alias（DebugGuidanceAlias）开了个后门，本脚本就是调用它。
#
# 用法：
#   tools/demo_navi.sh                      # 默认：重庆观音桥 1.3km
#   tools/demo_navi.sh 机场高速              # 预设路线
#   tools/demo_navi.sh "任意名字" 29.56 106.55 29.72 106.63
#
# 注意：这台测试机**没有 GPS**。不传起终点时会兜底到 NaviEngine.DEFAULT_START（天安门），
# 结果会算成北京→重庆一千多公里，看着像"定位错了"。所以一定要显式传。
set -euo pipefail

ADB="${ADB:-adb}"
PKG=com.motohud.ble
ALIAS="$PKG/.ui.navi.DebugGuidanceAlias"

name="${1:-观音桥}"
case "$name" in
  观音桥)  lat1=29.56280 lng1=106.55120 lat2=29.57500 lng2=106.53200 ;;
  机场高速) lat1=29.56280 lng1=106.55120 lat2=29.71900 lng2=106.63000 ;;
  *)
    lat1="${2:?需要 from_lat}"; lng1="${3:?需要 from_lng}"
    lat2="${4:?需要 to_lat}";   lng2="${5:?需要 to_lng}"
    ;;
esac

# 横屏（导航页主要面向横屏车机场景；不设的话截图会是竖屏，右栏形态看不出问题）
"$ADB" shell settings put system accelerometer_rotation 0 >/dev/null
"$ADB" shell settings put system user_rotation 1 >/dev/null
# 屏幕休眠会让 screencap 全黑、也会让 tick 停摆
"$ADB" shell svc power stayon true >/dev/null

"$ADB" shell am force-stop "$PKG"
sleep 2
"$ADB" logcat -c
"$ADB" shell am start -n "$ALIAS" \
  --es extra_name "$name" \
  --ed extra_from_lat "$lat1" --ed extra_from_lng "$lng1" \
  --ed extra_lat "$lat2" --ed extra_lng "$lng2" \
  --ez extra_emulator true >/dev/null

echo "已启动「$name」模拟导航"
echo "看放大图：  $ADB logcat -s NaviCross:V"
echo "看播报：    $ADB logcat -s GuidanceVoice:V"
