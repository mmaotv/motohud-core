#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""离线验证播报阶梯与连续路口压缩（复刻 Kotlin 逻辑，不依赖真机 GPS）。

真机上验证"位移相关"的播报逻辑很麻烦：高德定位走自有服务端网络定位，
`cmd location set-test-provider-location` 驱动不了它（已验证）。所以照惯例
用 Python 逐行复刻 Kotlin 判断，跑固定场景。

复刻对象：
  app/src/main/java/com/motohud/ble/ui/navi/GuidanceActivity.kt  announceApproaching
  app/src/main/java/com/motohud/ble/navi/VoiceGuidance.kt        compressTail / closeDistOf

用法：
    python tools/verify_speech_ladder.py
"""

import sys

# 与 GuidanceActivity 的 TURN_THRESHOLDS 一致（临门档由 closeDistOf 替换）
TURN_THRESHOLDS = [1_100, 550, 50]
# VoiceGuidance
CHAIN_AT_M = 250
COMPRESS_MAX_TOTAL_M = 800.0
COMPRESS_MAX_TAIL = 2


def close_dist_of(speed_kmh: float) -> int:
    if speed_kmh >= 65:
        return 80
    if speed_kmh >= 45:
        return 60
    if speed_kmh >= 25:
        return 45
    return 30


def thresholds_for(speed_kmh: float) -> list:
    """最后一档换成按车速算出的临门距离，其余不动（与 withCloseDist 一致）"""
    out = list(TURN_THRESHOLDS)
    out[-1] = close_dist_of(speed_kmh)
    return out


def simulate_run(step_len: float, speed_kmh: float, tick_s: float = 1.0):
    """沿一段路走完，每 tick 检查一次该不该播，返回触发的档位列表。"""
    thresholds = thresholds_for(speed_kmh)
    mask = 0
    fired = []
    travelled = 0.0
    per_tick = speed_kmh / 3.6 * tick_s
    while travelled <= step_len:
        rem = int(step_len - travelled)
        # 每 tick 刷新临门档（与 announceApproaching 一致）
        thresholds = [*thresholds[:-1], close_dist_of(speed_kmh)]
        chosen = -1
        for i, t in enumerate(thresholds):
            if rem <= t and not (mask >> i) & 1:
                chosen = i
        if chosen >= 0:
            for i in range(chosen + 1):
                mask |= 1 << i
            fired.append((rem, thresholds[chosen]))
        travelled += per_tick
    return fired


def compress_tail(steps, index, remain_m):
    """复刻 VoiceGuidance.compressTail（含"当前动作也算上一句"的复读保护）"""
    if remain_m > CHAIN_AT_M:
        return []
    cur_act = steps[index].get("act", "") if index < len(steps) else ""
    out = []
    acc = 0.0
    last = cur_act
    for s in steps[index + 1:]:
        if len(out) >= COMPRESS_MAX_TAIL:
            break
        d = s["distanceM"]
        if d <= 0:
            break
        if acc + d > COMPRESS_MAX_TOTAL_M:
            break
        act = s.get("act", "")
        if not act:
            break
        if act != last:
            out.append(act)
            last = act
        acc += d
    return out


def case_ladder():
    print("== 场景一：播报阶梯次数（高德 mixfork0 是 1100 + 550 + 临门 = 3 次）==")
    ok = True
    for speed in (15, 30, 50, 70, 90):
        fired = simulate_run(step_len=3_000, speed_kmh=speed)
        count = len(fired)
        marks = ", ".join("%dm@%dm" % (r, t) for r, t in fired)
        good = count == 3
        ok &= good
        print("  车速 %2d km/h → %d 次  [%s]  %s"
              % (speed, count, marks, "OK" if good else "!! 次数不对"))
    return ok


def case_ladder_desc():
    print("== 场景二：触发距离必须单调递减（不能先 550 再 1100）==")
    ok = True
    for speed in (15, 30, 50, 70, 90):
        fired = simulate_run(step_len=3_000, speed_kmh=speed)
        remains = [r for r, _ in fired]
        good = all(a > b for a, b in zip(remains, remains[1:]))
        ok &= good
        print("  车速 %2d → 剩余 %s  %s" % (speed, remains, "OK" if good else "!! 顺序错"))
    return ok


def case_compress():
    print("== 场景三：连续路口压缩（高德 compress，initMaxDist=800）==")
    steps = [
        {"distanceM": 200, "act": "右转进入A路"},
        {"distanceM": 300, "act": "左转进入B路"},
        {"distanceM": 300, "act": "右转进入C路"},
        {"distanceM": 900, "act": "左转进入D路"},
    ]
    ok = True

    got = compress_tail(steps, 0, 200)
    want = ["左转进入B路", "右转进入C路"]
    good = got == want
    ok &= good
    print("  距路口 200m，后两段 300+300 → %s  %s" % (got, "OK" if good else "!! 期望 %s" % want))

    got = compress_tail(steps, 0, 400)
    good = got == []
    ok &= good
    print("  距路口 400m（>250 不预告）→ %s  %s" % (got, "OK" if good else "!! 应为空"))

    # 跨度 900 > 800 → 不并
    got = compress_tail(steps, 3, 200)
    good = got == []
    ok &= good
    print("  下一段 900m（>800 不算连续）→ %s  %s" % (got, "OK" if good else "!! 应为空"))

    # 封顶两段
    many = [{"distanceM": 200, "act": "起点"}]
    many += [{"distanceM": 150, "act": "动作%d" % i} for i in range(1, 6)]
    got = compress_tail(many, 0, 200)
    good = len(got) == COMPRESS_MAX_TAIL
    ok &= good
    print("  四段都短 → 只并 %d 段 %s  %s"
          % (len(got), got, "OK" if good else "!! 封顶失效"))

    # 复读保护
    dup = [{"distanceM": 200, "act": "右转进入A路"},
           {"distanceM": 200, "act": "右转进入A路"},
           {"distanceM": 200, "act": "左转进入B路"}]
    got = compress_tail(dup, 0, 200)
    good = got == ["左转进入B路"]
    ok &= good
    print("  连续两段同一句 → %s  %s" % (got, "OK" if good else "!! 复读没滤掉"))
    return ok


def case_arbiter():
    print("== 场景四：播报仲裁（高德 priority 表范式）==")
    # 复刻 SpeechArbiter.submit
    class A:
        def __init__(self):
            self.cur = None
            self.until = 0

        def submit(self, cue, text, now):
            busy = now < self.until
            if not busy:
                self.cur, self.until = cue, now + len(text) * 220 + 250
                return "PLAY"
            if cue[0] > self.cur[0]:
                self.cur, self.until = cue, now + len(text) * 220 + 250
                return "PLAY"
            if cue[0] < self.cur[0]:
                return "DROP"
            if cue[1] >= self.cur[1]:
                self.cur, self.until = cue, now + len(text) * 220 + 250
                return "PLAY"
            return "DROP"

    APPROACH = (60, 100, "路口预警")
    CLOSE = (80, 100, "临门动作")
    REROUTE = (90, 100, "偏航重算")
    UI = (20, 30, "操作确认")
    ok = True

    a = A()
    r1 = a.submit(APPROACH, "前方 500 米右转进入南滨路", 0)
    r2 = a.submit(UI, "语音播报已开启", 500)          # 低优先级 → 丢
    good = r1 == "PLAY" and r2 == "DROP"
    ok &= good
    print("  预警说着时插一句操作确认 → %s  %s" % (r2, "OK" if good else "!! 该丢弃"))

    a = A()
    a.submit(APPROACH, "前方 500 米右转进入南滨路", 0)
    r = a.submit(REROUTE, "您已偏离路线，正在重新规划", 500)
    good = r == "PLAY"
    ok &= good
    print("  预警说着时偏航 → %s  %s" % (r, "OK" if good else "!! 该抢播"))

    a = A()
    a.submit(CLOSE, "右转", 0)
    r = a.submit(APPROACH, "前方 200 米右转", 300)
    good = r == "DROP"
    ok &= good
    print("  临门「右转」说着时来个远处预警 → %s  %s" % (r, "OK" if good else "!! 该丢弃"))

    a = A()
    r = a.submit(UI, "语音播报已开启", 0)
    r2 = a.submit(APPROACH, "前方 1 公里右转", 10_000)  # 前面早说完了
    good = r == "PLAY" and r2 == "PLAY"
    ok &= good
    print("  说完了再来新播报 → %s  %s" % (r2, "OK" if good else "!! 该播放"))
    return ok


def main():
    results = [case_ladder(), case_ladder_desc(), case_compress(), case_arbiter()]
    print()
    if all(results):
        print("全部通过")
        return 0
    print("有场景未通过，见上面 !! 标记")
    return 1


if __name__ == "__main__":
    sys.exit(main())
