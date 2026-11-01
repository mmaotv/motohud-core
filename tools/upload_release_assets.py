#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
补传 Release 附件（上传断了重跑这个，不要重跑 publish_release.py ——
那个会先把 Release 删掉重建）。

为什么要单独一个：
  · github.com:443 直连不通，只有 uploads.github.com 可达，链路不稳；
  · 116MB 的包传到一半被掐是常事（实测 2m41s 时 Errno 10053），
    GitHub 又不支持断点续传，只能整包重发；
  · 所以这里做「先查已有 asset → 缺哪个传哪个 → 失败重试 N 次」。
"""
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

REPO = "mmaotv/motohud-core"
TAG = "v0.0.27"
ASSETS = [
    ("release-v0.0.27/motohud-v0.0.27-arm64.apk", "motohud-v0.0.27-arm64.apk"),
    ("release-v0.0.27/motohud-v0.0.27-armv7.apk", "motohud-v0.0.27-armv7.apk"),
]
RETRIES = 4

TOKEN = open(r"D:/APPDEV/github token.txt", encoding="utf-8").read().strip()
API = "https://api.github.com"
UPLOAD = "https://uploads.github.com"


def call(method, url, data=None, ctype=None, timeout=120):
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Authorization", "Bearer " + TOKEN)
    req.add_header("Accept", "application/vnd.github+json")
    if ctype:
        req.add_header("Content-Type", ctype)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.getcode(), r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")
    except Exception as e:  # 网络层（含 Errno 10053）
        return -1, repr(e)


def main():
    code, resp = call("GET", f"{API}/repos/{REPO}/releases/tags/{TAG}")
    if code != 200:
        print("查不到 Release:", code, resp)
        sys.exit(1)
    rel = json.loads(resp)
    rel_id = rel["id"]
    existing = {a["name"] for a in rel.get("assets", [])}
    print(f"Release id={rel_id}，已有附件: {existing or '无'}")

    for path, name in ASSETS:
        if name in existing:
            print(f"跳过（已存在）: {name}")
            continue
        if not os.path.isfile(path):
            print("缺失文件:", path)
            sys.exit(1)
        size = os.path.getsize(path)
        print(f"上传 {name} ({size/1048576:.1f} MB) ...", flush=True)
        ok = False
        for attempt in range(1, RETRIES + 1):
            with open(path, "rb") as f:
                data = f.read()
            url = (f"{UPLOAD}/repos/{REPO}/releases/{rel_id}/assets"
                   f"?name={urllib.parse.quote(name)}")
            t0 = time.time()
            code, resp = call("POST", url, data, "application/vnd.android.package-archive",
                              timeout=1800)
            if code in (200, 201):
                print(f"  成功，用时 {time.time()-t0:.0f}s")
                ok = True
                break
            print(f"  第 {attempt} 次失败: {code} {resp[:200]}", flush=True)
            time.sleep(5)
        if not ok:
            print("放弃:", name)
            sys.exit(1)

    # 复核
    code, resp = call("GET", f"{API}/repos/{REPO}/releases/{rel_id}")
    rel = json.loads(resp)
    print("最终附件:")
    for a in rel.get("assets", []):
        print(f"  {a['name']}  {a['size']/1048576:.1f} MB  {a['state']}")
    print("页面:", f"https://github.com/{REPO}/releases/tag/{TAG}")


if __name__ == "__main__":
    main()
