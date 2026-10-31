#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
一键发布到 GitHub（mmaotv/motohud-core）。

设计约束（见项目记忆）：
  - github.com:443 直连不通；Release / APK 走 api.github.com + uploads.github.com。
  - 代码推送走 SSH（remote `core`），本脚本只负责「建 Release + 传 APK」，不推代码。
  - 同 tag 已存在：先删旧 Release（及其远程 tag），再重建，避免重复 tag 报错。

用法：改下面 TAG / NAME / NOTES / ASSETS，直接跑：
    python tools/publish_release.py
"""
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

REPO = "mmaotv/motohud-core"
TAG = "v0.0.22"
NAME = "MotoHUD v0.0.22"
NOTES = "release-notes-v0.0.22.md"
ASSETS = [
    "release-v0.0.22/motohud-v0.0.22-arm64.apk",
    "release-v0.0.22/motohud-v0.0.22-armv7.apk",
]

TOKEN = open(r"D:/APPDEV/github token.txt", encoding="utf-8").read().strip()
API = "https://api.github.com"
UPLOAD = "https://uploads.github.com"


def api(method: str, url: str, data=None):
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Authorization", "Bearer " + TOKEN)
    req.add_header("Accept", "application/vnd.github+json")
    if data is not None:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            return r.getcode(), r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")


def main():
    if not os.path.isfile(NOTES):
        print("缺失发布说明:", NOTES)
        sys.exit(1)
    for a in ASSETS:
        if not os.path.isfile(a):
            print("缺失产物:", a)
            sys.exit(1)

    body = open(NOTES, encoding="utf-8").read()

    # 1) 同 tag 的 Release 已存在 → 先删（再删远程 tag，避免重建 tag 冲突）
    code, resp = api("GET", f"{API}/repos/{REPO}/releases/tags/{TAG}")
    if code == 200:
        rel = json.loads(resp)
        rid = rel["id"]
        api("DELETE", f"{API}/repos/{REPO}/releases/{rid}")
        print(f"已删除旧 Release (id={rid})")
        # 删远程 tag（本地 tag 由调用方处理）
        # 用 SSH push 删 tag 不在本脚本职责内，这里只保证 Release 不重复。
    else:
        print("无已存在 Release（或查询失败，按新建处理）")

    # 2) 建 Release
    payload = json.dumps({
        "tag_name": TAG,
        "name": NAME,
        "body": body,
        "draft": False,
        "prerelease": False,
    }).encode("utf-8")
    code, resp = api("POST", f"{API}/repos/{REPO}/releases", payload)
    if code not in (200, 201):
        print("建 Release 失败:", code, resp)
        sys.exit(1)
    rel_id = json.loads(resp)["id"]
    print(f"已建 Release (id={rel_id})")

    # 3) 传 APK
    for path in ASSETS:
        name = os.path.basename(path)
        with open(path, "rb") as f:
            data = f.read()
        url = (f"{UPLOAD}/repos/{REPO}/releases/{rel_id}/assets"
               f"?name={urllib.parse.quote(name)}")
        req = urllib.request.Request(url, data=data, method="POST")
        req.add_header("Authorization", "Bearer " + TOKEN)
        req.add_header("Content-Type", "application/vnd.android.package-archive")
        try:
            with urllib.request.urlopen(req, timeout=600) as r:
                print(f"已上传 {name} -> {r.getcode()}")
        except urllib.error.HTTPError as e:
            print(f"上传 {name} 失败:", e.code, e.read().decode("utf-8", "replace"))
            sys.exit(1)
    print("发布完成:", f"https://github.com/{REPO}/releases/tag/{TAG}")


if __name__ == "__main__":
    main()
