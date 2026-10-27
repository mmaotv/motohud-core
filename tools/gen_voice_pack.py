"""
内置语音包生成器（构建期一次性工具，不参与 APK 构建）。

用法：
    python tools/gen_voice_pack.py

输出：app/src/main/res/raw/vp_*.mp3

设计：导航播报高度模板化，与其把几十 MB 的神经 TTS 模型塞进包里，
不如把「前方 / 300米 / 右转」这类片段在构建期用神经网络 TTS 合成好，
运行时按顺序播放。32 段片段合计约 0.5MB，音质接近云端，且完全离线、
无 JNI 依赖 —— 系统没装任何 TTS 引擎的机器也能正常播报。

代价：念不出路名（只念"前方 300 米右转"）。这也是一线车机导航的常见口径。
"""
import asyncio
import os
import sys

import edge_tts

# Xiaoxiao 是微软最清晰的中文女声之一；+8% 语速贴近导航播报节奏
VOICE = "zh-CN-XiaoxiaoNeural"
RATE = "+8%"

CLIPS = {
    # 前缀
    "vp_front": "前方",
    # 距离档位（播报距离一律取到最近的档位，不念精确米数）
    "vp_d50": "50米",
    "vp_d100": "100米",
    "vp_d200": "200米",
    "vp_d300": "300米",
    "vp_d500": "500米",
    "vp_d800": "800米",
    "vp_d1k": "1公里",
    "vp_d2k": "2公里",
    "vp_d5k": "5公里",
    "vp_d10k": "10公里",
    # 全程（整句，避免"全程约"+"5公里"拼接导致 6.7km 被念成 5 公里）
    "vp_t1": "全程约1公里",
    "vp_t2": "全程约2公里",
    "vp_t3": "全程约3公里",
    "vp_t4": "全程约4公里",
    "vp_t5": "全程约5公里",
    "vp_t6": "全程约6公里",
    "vp_t7": "全程约7公里",
    "vp_t8": "全程约8公里",
    "vp_t9": "全程约9公里",
    "vp_t10": "全程约10公里",
    "vp_t15": "全程约15公里",
    "vp_t20": "全程约20公里",
    "vp_t30": "全程约30公里",
    "vp_t50": "全程约50公里以上",
    # 动作（与 NaviEngine.IconType 一一对应）
    "vp_a_straight": "直行",
    "vp_a_left": "左转",
    "vp_a_right": "右转",
    "vp_a_left_front": "向左前方行驶",
    "vp_a_right_front": "向右前方行驶",
    "vp_a_left_back": "向左后方行驶",
    "vp_a_right_back": "向右后方行驶",
    "vp_a_uturn": "掉头",
    "vp_a_roundabout_in": "进入环岛",
    "vp_a_roundabout_out": "驶出环岛",
    "vp_a_waypoint": "到达途经点",
    "vp_a_dest": "到达目的地",
    "vp_a_merge_left": "汇入左侧主路",
    "vp_a_merge_right": "汇入右侧主路",
    # 固定句
    "vp_start": "开始导航",
    "vp_reroute": "已重新规划路线",
    "vp_gps_weak": "GPS信号弱，位置可能不准",
    "vp_arrived": "已到达目的地",
    "vp_end": "导航结束",
    "vp_exited": "已退出导航",
    "vp_voice_on": "语音已开启",
    "vp_voice_off": "语音已关闭",
}


async def one(name: str, text: str, out_dir: str) -> None:
    path = os.path.join(out_dir, name + ".mp3")
    if os.path.exists(path) and os.path.getsize(path) > 1000:
        print("skip", name)
        return
    # 重试：网络抖动时不要让整批失败
    for attempt in range(3):
        try:
            await edge_tts.Communicate(text, VOICE, rate=RATE).save(path)
            print("ok  ", name, os.path.getsize(path), "B")
            return
        except Exception as e:  # noqa: BLE001
            print("retry", name, attempt, e)
            await asyncio.sleep(1.0)
    print("FAIL", name)


async def main() -> None:
    out_dir = os.path.join(
        os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
        "app", "src", "main", "res", "raw",
    )
    os.makedirs(out_dir, exist_ok=True)
    for name, text in CLIPS.items():
        await one(name, text, out_dir)
    total = sum(
        os.path.getsize(os.path.join(out_dir, n + ".mp3"))
        for n in CLIPS
        if os.path.exists(os.path.join(out_dir, n + ".mp3"))
    )
    print(f"\n共 {len(CLIPS)} 段，合计 {total / 1024:.0f} KB")


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
