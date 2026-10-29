# -*- coding: utf-8 -*-
"""生成内置语音包（assets/voicepack）。

音色与 0.0.5 那批 `res/raw/vp_*.mp3` 完全一致：edge-tts 的 zh-CN-XiaoxiaoNeural，
语速 +8%。那是本项目的「第一版语音」，用户明确要求沿用它，参数不要再改。
（旧版 48 段 res/raw 已随 VoicePack 一起删除，产物不再进 res —— 150+ 个资源 id
没有意义，assets + 索引文件更直接。）

用法：
    python tools/gen_voice_pack.py

产物：
    app/src/main/assets/voicepack/<md5>.mp3   每条一句话，文件名是文本 md5
    app/src/main/assets/voicepack/index.txt   UTF-8，每行 `文本<TAB>文件名`

## 清单为什么是这些

播放端（PresetPackSpeaker）拿到一句播报文本后：
  1. 整句命中 → 单段播放，没有拼接停顿；
  2. 否则从左到右**贪心匹配最长已知片段**，匹配不上的字（路名）跳过。

所以要覆盖三种粒度：
  - **原子**：动作词、距离词（带/不带「前方」）、路口次序、系统提示；
  - **常用整句**：距离 × 常用动作（少一段拼接就少一次停顿）；
  - **整句句式**：「继续直行 X」「前方 X 米路口直行」「进入环岛，第 N 个出口」。

⚠ 与代码强耦合，改文案必须同步改这里，否则命中不了：
  `navi/SpeechScript.kt`（action / distance / span）
  `navi/VoiceGuidance.kt`（entry / pre / actionOnly 的句式）
  `navi/tts/VoiceLibrary.kt`（NOTICES）
"""
import asyncio
import hashlib
import pathlib
import sys

VOICE = "zh-CN-XiaoxiaoNeural"
RATE = "+8%"

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "app/src/main/assets/voicepack"

# 与 SpeechScript.action() 一致
ACTIONS = [
    "左转", "右转", "向左前方行驶", "向右前方行驶",
    "向左后方行驶", "向右后方行驶", "掉头", "直行",
    "进入环岛", "靠左进入环岛", "驶出环岛", "向左并线", "向右并线",
]
# 与 SpeechScript.distance() 一致
DISTANCES = [
    "前方 50 米", "前方 100 米", "前方 200 米", "前方 300 米", "前方 500 米",
    "前方 800 米", "前方 1 公里", "前方 2 公里", "前方 5 公里", "前方 10 公里",
]
# 与 SpeechScript.span() 一致
SPANS = [
    "50 米", "100 米", "200 米", "300 米", "500 米",
    "800 米", "1 公里", "2 公里", "5 公里", "10 公里",
]
# 与 VoiceLibrary.NOTICES 一致
NOTICES = [
    "开始导航",
    "即将到达目的地",
    "已到达目的地",
    "已到达途经点",
    "您已偏离路线，正在重新规划",
    "已为您重新规划路线",
    "前方道路拥堵，正在寻找更快路线",
    "已为您切换到更快路线",
    "语音播报已开启",
    "语音播报已关闭",
    "正在搜索卫星信号",
    "卫星信号弱，请注意定位偏差",
    "请注意安全驾驶",
    "前方路口请减速慢行",
    "请保持直行",
    "请注意避让行人",
]
# 整句优先给这些动作做组合：骑行中最常听到的几个
COMMON_ACTIONS = ["左转", "右转", "向左前方行驶", "向右前方行驶", "掉头", "进入环岛"]


def build_phrases():
    phrases, seen = [], set()

    def add(t):
        t = t.strip()
        if t and t not in seen:
            seen.add(t)
            phrases.append(t)

    # 1) 原子
    for a in ACTIONS:
        add(a)
    for d in DISTANCES:
        add(d)
    for s in SPANS:
        add(s)
    for n in NOTICES:
        add(n)
    add("路口直行")
    for n in range(2, 10):
        add(f"第 {n} 个路口")

    # 2) 常用整句：距离 × 常用动作
    for d in DISTANCES:
        for a in COMMON_ACTIONS:
            add(f"{d}{a}")

    # 3) 整句句式（entry 兜底 / 直行路口 / 环岛出口）
    for s in SPANS:
        add(f"继续直行 {s}")
    for d in DISTANCES:
        add(f"{d}路口直行")
    for n in range(2, 9):
        add(f"进入环岛，走第 {n} 个出口")
    for n in range(1, 9):
        add(f"进入环岛，第 {n} 个出口")
    return phrases


async def synth(text, path):
    import edge_tts
    for attempt in range(3):
        try:
            await edge_tts.Communicate(text, VOICE, rate=RATE).save(str(path))
            if path.exists() and path.stat().st_size > 1000:
                return
        except Exception as e:  # noqa: BLE001
            print("  retry", text, attempt, e)
            await asyncio.sleep(1.5 * (attempt + 1))
    print("  FAIL", text)


async def main():
    OUT.mkdir(parents=True, exist_ok=True)
    phrases = build_phrases()
    print(f"共 {len(phrases)} 条 → {OUT}")
    sem = asyncio.Semaphore(4)
    done = 0
    index_lines = []

    async def one(text):
        nonlocal done
        name = hashlib.md5(text.encode("utf-8")).hexdigest() + ".mp3"
        path = OUT / name
        async with sem:
            if not (path.exists() and path.stat().st_size > 1000):
                await synth(text, path)
            index_lines.append(f"{text}\t{name}")
            done += 1
            if done % 20 == 0:
                print(f"  {done}/{len(phrases)}")

    await asyncio.gather(*(one(t) for t in phrases))
    index_lines.sort()
    (OUT / "index.txt").write_text("\n".join(index_lines) + "\n", encoding="utf-8")
    total = sum(p.stat().st_size for p in OUT.glob("*.mp3"))
    print(f"完成：{len(index_lines)} 条，{total / 1024 / 1024:.2f} MB")


if __name__ == "__main__":
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    asyncio.run(main())
