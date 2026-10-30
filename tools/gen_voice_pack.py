# -*- coding: utf-8 -*-
"""生成内置语音包（assets/voicepack）。

多音色：每套音色一个子目录，默认（第一套）直接放 voicepack 根目录。

为什么默认那套不放进子目录：0.0.5 以来的 150+ 个 mp3 已经在根目录了，
换目录要连带迁移所有资源、还要保证老版本升级时不出错 —— 收益为零。
新加的音色走子目录，播放端按 [PresetVoice] 的 dir 字段拼路径即可。

用法：
    python tools/gen_voice_pack.py            # 全部音色，缺的补
    python tools/gen_voice_pack.py xiao       # 只补某一套

产物：
    app/src/main/assets/voicepack/<md5>.mp3          默认音色
    app/src/main/assets/voicepack/<md5>.mp3 同级 index.txt
    app/src/main/assets/voicepack/<voiceId>/...      其余音色，各有 index.txt

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
  `navi/tts/PresetVoice.kt`（音色 id 与子目录，改了这里必须跟着改）
"""
import asyncio
import hashlib
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
ASSETS = ROOT / "app/src/main/assets/voicepack"

# (音色 id, edge-tts voice, 语速, 子目录) —— 与 navi/tts/PresetVoice.kt 逐项对应。
# 第一套的子目录是 ""（根目录），见文件头说明。
VOICES = [
    ("xiao", "zh-CN-XiaoxiaoNeural", "+8%", ""),
    ("yun", "zh-CN-YunjianNeural", "+10%", "yun"),
    ("yi", "zh-CN-XiaoyiNeural", "+10%", "yi"),
]

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
    # 导航中追加途经点（GuidanceActivity 的 addWaypointLauncher 会念这句）
    "已添加途经点，重新规划路线",
    "已为您重新规划路线，避开拥堵路段",
]
# 整句优先给这些动作做组合：骑行中最常听到的几个
COMMON_ACTIONS = ["左转", "右转", "向左前方行驶", "向右前方行驶", "掉头", "进入环岛"]

# 沿途道路要素提示（与 navi/RoadFeatureNarrator.kt 的 presetPack 分支逐字一致）。
# 这里**刻意不带专有名词**（不写"向阳隧道""渝澳大桥"）：
# 内置包是 153 条预录片段，念不出任意桥名/隧道名，带名字只会被跳过、留下半句。
# 装了离线模型的链路会走带名称的完整句（现场合成）。
ROAD_FEATURES = [
    "前方进入隧道，请开灯",
    "前方上桥，注意横风",
    "前方进入立交，注意车道",
    "前方进入匝道",
    "前方人行天桥，注意行人",
]

# 弯道预警（与 navi/RoadShape.kt 的 CurveNarrator.fixedText 逐字一致）。
# 弯道是纯几何自算的结果（polyline 曲率），内置包只需要三种固定说法。
CURVES = [
    "前方连续弯道，请减速",
    "前方急转弯向左，请减速",
    "前方急转弯向右，请减速",
]

# 坡度提示（与 navi/TerrainSense.kt 的 TerrainNarrator.fixedText 逐字一致）。
# 注意是「连续下坡」而不是「前方下坡」——坡度只能测已经走过的路，见 TerrainSense 注释。
TERRAIN = [
    "连续下坡，注意控制车速",
    "连续上坡，注意动力",
]

# 驾驶行为反馈（与 navi/DriveBehavior.kt 的 DriveBehaviorNarrator.fixedText 逐字一致）。
# 默认不播，但用户可能在设置里打开，片段必须先备好。
BEHAVIOR = [
    "急刹车，注意后方车辆",
    "加速过猛，注意前方路况",
    "转弯过快，注意车速",
]


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
    for n in ROAD_FEATURES:
        add(n)
    for n in CURVES:
        add(n)
    for n in TERRAIN:
        add(n)
    for n in BEHAVIOR:
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


async def synth(text, path, voice, rate):
    import edge_tts
    for attempt in range(3):
        try:
            await edge_tts.Communicate(text, voice, rate=rate).save(str(path))
            if path.exists() and path.stat().st_size > 1000:
                return
        except Exception as e:  # noqa: BLE001
            print("  retry", text, attempt, e)
            await asyncio.sleep(1.5 * (attempt + 1))
    print("  FAIL", text)


async def build_one(voice_id, voice, rate, subdir, phrases):
    """生成一套音色。已有的 mp3 直接复用（按 md5 命名，跨音色同文本同名但目录不同）。"""
    out = ASSETS / subdir if subdir else ASSETS
    out.mkdir(parents=True, exist_ok=True)
    print(f"\n[{voice_id}] {voice} {rate} → {out.relative_to(ROOT)}  ({len(phrases)} 条)")
    sem = asyncio.Semaphore(4)
    done = 0
    index_lines = []

    async def one(text):
        nonlocal done
        name = hashlib.md5(text.encode("utf-8")).hexdigest() + ".mp3"
        path = out / name
        async with sem:
            if not (path.exists() and path.stat().st_size > 1000):
                await synth(text, path, voice, rate)
            index_lines.append(f"{text}\t{name}")
            done += 1
            if done % 40 == 0:
                print(f"  {done}/{len(phrases)}")

    await asyncio.gather(*(one(t) for t in phrases))
    index_lines.sort()
    (out / "index.txt").write_text("\n".join(index_lines) + "\n", encoding="utf-8")
    total = sum(p.stat().st_size for p in out.glob("*.mp3"))
    print(f"[{voice_id}] 完成：{len(index_lines)} 条，{total / 1024 / 1024:.2f} MB")


async def main():
    wanted = set(sys.argv[1:])
    phrases = build_phrases()
    print(f"清单共 {len(phrases)} 条")
    for voice_id, voice, rate, subdir in VOICES:
        if wanted and voice_id not in wanted:
            continue
        await build_one(voice_id, voice, rate, subdir, phrases)


if __name__ == "__main__":
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    asyncio.run(main())
