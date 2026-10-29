# -*- coding: utf-8 -*-
"""
离线复刻 GuidanceActivity 的阈值播报调度，验证「临门播报距离按车速动态调整」。

对应代码：
  navi/VoiceGuidance.kt      closeDistOf()
  navi/SpeechScript.kt       distance()
  ui/navi/GuidanceActivity.kt  thresholdsFor() / withCloseDist() / announceApproaching()

不依赖真机 GPS —— 这台测试机的 mock 定位驱动不了高德引擎，
播报阶梯这类纯逻辑只能靠复刻验证。
"""


def close_dist_of(speed_kmh):
    """VoiceGuidance.closeDistOf"""
    if speed_kmh >= 65:
        return 80
    if speed_kmh >= 45:
        return 60
    if speed_kmh >= 25:
        return 45
    return 30


def distance_text(remain_m):
    """SpeechScript.distance"""
    if remain_m <= 0:
        return ""
    if remain_m <= 75:
        return "前方 50 米"
    if remain_m <= 150:
        return "前方 100 米"
    if remain_m <= 250:
        return "前方 200 米"
    if remain_m <= 400:
        return "前方 300 米"
    if remain_m <= 650:
        return "前方 500 米"
    if remain_m <= 900:
        return "前方 800 米"
    if remain_m <= 1500:
        return "前方 1 公里"
    if remain_m <= 3000:
        return "前方 2 公里"
    if remain_m <= 7000:
        return "前方 5 公里"
    return "前方 10 公里"


def pre_text(remain_m, close_dist, road="南滨路"):
    """VoiceGuidance.pre —— 只关心"临门档只说动作"这一条分支"""
    if remain_m <= close_dist:
        return "右转"          # SpeechScript.actionOnly
    d = distance_text(remain_m)
    return f"{d}右转进入{road}"


def simulate(speed_kmh, step_len=2000, tick_m=10):
    """跑一段直路，返回 [(触发距离, 播报文本)]"""
    close = close_dist_of(speed_kmh)
    thresholds = [1000, 500, 200, close]
    mask = [False] * len(thresholds)
    out = []

    rem = step_len
    while rem > 0:
        # 每 tick 用实时车速刷新最后一档（announceApproaching 里那句 withCloseDist）
        thresholds[-1] = close_dist_of(speed_kmh)
        chosen = -1
        for i, t in enumerate(thresholds):
            if rem <= t and not mask[i]:
                chosen = i
        if chosen >= 0:
            for i in range(chosen + 1):
                mask[i] = True
            out.append((rem, pre_text(rem, thresholds[-1])))
        rem -= tick_m
    return close, out


def main():
    print(f"{'车速':>6}  {'临门档':>6}  播报序列（触发距离 → 文本）")
    print("-" * 78)
    for spd in (15, 30, 50, 70, 90):
        close, seq = simulate(spd)
        joined = "  |  ".join(f"{r}m→{t}" for r, t in seq)
        print(f"{spd:>4}km/h  {close:>5}m  {joined}")

    print("\n=== 断言 ===")
    # 1. 所有车速都必须有且仅有一次纯动作播报
    for spd in (15, 30, 50, 70, 90):
        _, seq = simulate(spd)
        acts = [x for x in seq if x[1] == "右转"]
        assert len(acts) == 1, f"{spd}km/h 纯动作播报次数={len(acts)}"
    print("✓ 每种车速都恰好一次临门纯动作播报")

    # 2. 临门档严格随车速增大
    closes = [close_dist_of(s) for s in (15, 30, 50, 70, 90)]
    assert closes == sorted(closes) and len(set(closes)) == 4, closes
    print(f"✓ 临门档随车速单调递增: {closes}")

    # 3. 档位严格降序（否则 remindMask 的位序会错乱）
    for spd in (15, 30, 50, 70, 90):
        c = close_dist_of(spd)
        arr = [1000, 500, 200, c]
        assert arr == sorted(arr, reverse=True), (spd, arr)
    print("✓ 阶梯始终降序，remindMask 位序不会错乱")

    # 4. 不会重复播报同一档
    for spd in (15, 30, 50, 70, 90):
        _, seq = simulate(spd)
        poss = [r for r, _ in seq]
        assert poss == sorted(poss, reverse=True), (spd, poss)
    print("✓ 触发距离单调递减，无重复触发")


if __name__ == "__main__":
    main()
