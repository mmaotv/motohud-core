#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
准备高德 SDK（地图 / 搜索 / 定位）。

两条通道，默认走官网：

  【官网合包 · 推荐】高德从 V11 起不再往 Maven 发版（`com.amap.api:3dmap` 的
  latest 永远停在 10.0.600），想用新版只能下官网的整包。合包 jar 里地图、搜索、
  定位三者已经是一体的，不需要再去重合并；so 在旁边按 abi 分目录。

      python tools/prepare_amap_sdk.py                # 自动下载官网整包（约 120MB）
      python tools/prepare_amap_sdk.py --bundle DIR   # 用已经解压好的目录
      python tools/prepare_amap_sdk.py --bundle X.zip # 用官网下载的 zip

  【Maven · 备用】老的按 artifact 分别下载 + 合并去重。V11 拿不到，只在
  需要回退到 10.0.600 时用得上。

      python tools/prepare_amap_sdk.py --maven

不管哪条通道，最后都产出：
  app/libs/amap-sdk-<版本>.jar      纯 class 的 jar（jar 内嵌 assets 必须拿出来）
  app/src/main/{jniLibs,assets}/    按 abi / 目录铺好，旧 SDK 遗留会被清掉

仅使用 Python 标准库。SDK 版权归高德（AutoNavi）所有，本脚本不重新分发 SDK。
"""
import argparse
import io
import os
import re
import shutil
import struct
import sys
import urllib.request
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APP = os.path.join(ROOT, "app")
LIBS = os.path.join(APP, "libs")
JNI = os.path.join(APP, "src", "main", "jniLibs")
ASSETS = os.path.join(APP, "src", "main", "assets")
CACHE = os.path.join(ROOT, "tools", ".cache")

BUNDLE_URL = "https://a.amap.com/lbs/static/zip/AMap_Android_SDK_All.zip"
# 整包里还套着一层，这个是地图+搜索+定位合包
BUNDLE_INNER = "AMap3DMap_AMapSearch_AMapLocation.zip"

# Maven 通道（老路）。3dmap 的天花板就是 10.0.600，改不动。
MAVEN_ARTIFACTS = [
    ("3dmap", "10.0.600"),
    ("search", "9.7.1"),
    ("location", "11.3.101"),
]

# 我们自己往 assets 里放的东西，升级时必须留住（bikes 车型插画、voicepack 预合成语音）
LOCAL_ASSET_DIRS = {"bikes", "voicepack"}

ABIS = ("arm64-v8a", "armeabi-v7a")


# ---------------------------------------------------------------- 通用小工具

def load_align(data):
    """读 ELF 的 PT_LOAD 段 p_align —— 小于 0x4000 就是不支持 16KB 内存页。"""
    if data[:4] != b"\x7fELF":
        return []
    if data[4] == 2:
        phoff = struct.unpack_from("<Q", data, 0x20)[0]
        phentsize = struct.unpack_from("<H", data, 0x36)[0]
        phnum = struct.unpack_from("<H", data, 0x38)[0]
        align_off, align_fmt = 48, "<Q"
    else:
        phoff = struct.unpack_from("<I", data, 0x1C)[0]
        phentsize = struct.unpack_from("<H", data, 0x2A)[0]
        phnum = struct.unpack_from("<H", data, 0x2C)[0]
        align_off, align_fmt = 28, "<I"
    out = []
    for i in range(phnum):
        o = phoff + i * phentsize
        if struct.unpack_from("<I", data, o)[0] == 1:  # PT_LOAD
            out.append(struct.unpack_from(align_fmt, data, o + align_off)[0])
    return out


def write_jar(path, classes):
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as out:
        for name, data in classes.items():
            out.writestr(name, data)
    print("jar  -> %s（%.1fMB，%d 个类）"
          % (os.path.relpath(path, ROOT), os.path.getsize(path) / 1048576.0, len(classes)))


def drop_old_jars(keep):
    for name in os.listdir(LIBS):
        if name.startswith("amap-sdk") and name.endswith(".jar"):
            path = os.path.join(LIBS, name)
            if os.path.abspath(path) != os.path.abspath(keep):
                os.remove(path)
                print("  删除旧 jar:", name)


def write_natives(natives):
    """natives: {abi: {文件名: 字节}}"""
    for abi, files in natives.items():
        dst_dir = os.path.join(JNI, abi)
        os.makedirs(dst_dir, exist_ok=True)
        for name, data in files.items():
            # 文件名带版本号（libAMapSDK_MAP_v11_3_100.so），换成新版时旧的必须删掉，
            # 否则同一个库的两个版本会一起进 APK
            base = re.sub(r"_v[0-9_]+\.so$", ".so", name)
            for old in os.listdir(dst_dir):
                if os.path.isfile(os.path.join(dst_dir, old)) and old.endswith(".so") \
                        and re.sub(r"_v[0-9_]+\.so$", ".so", old) == base:
                    try:
                        os.remove(os.path.join(dst_dir, old))
                    except OSError as e:
                        print("  警告：旧 so 删不掉 %s (%s)，请手工删除后再打包" % (old, e.strerror))
            with open(os.path.join(dst_dir, name), "wb") as f:
                f.write(data)
            aligns = load_align(data)
            # 16KB 内存页只存在于 64 位设备，armeabi-v7a 的 so 仍是 4KB 对齐属正常
            if abi == "arm64-v8a":
                flag = "16KB-OK" if all(a >= 0x4000 for a in aligns if a) else "警告: 仅 4KB，16KB 设备会加载失败"
            else:
                flag = "32 位无需 16KB 对齐"
            print("so   -> %s/%s  %.1fMB  align=%s  %s"
                  % (abi, name, len(data) / 1048576.0, [hex(a) for a in aligns], flag))


def write_assets(assets):
    """assets: {相对路径: 字节}。先清旧 SDK 资源，再写新的，自有目录搬走再搬回。"""
    keep_dir = os.path.join(APP, "src", "main", "_assets_keep")
    if os.path.isdir(keep_dir):
        shutil.rmtree(keep_dir)
    os.makedirs(keep_dir)
    kept = []
    for name in LOCAL_ASSET_DIRS:
        src = os.path.join(ASSETS, name)
        if os.path.isdir(src):
            shutil.copytree(src, os.path.join(keep_dir, name))
            kept.append(name)

    removed = 0
    failed = []
    if os.path.isdir(ASSETS):
        for child in os.listdir(ASSETS):
            if child in LOCAL_ASSET_DIRS:
                continue
            path = os.path.join(ASSETS, child)
            try:
                shutil.rmtree(path) if os.path.isdir(path) else os.remove(path)
                removed += 1
            except OSError as e:
                # 某些环境（带删除钩子的沙箱）会拦批量删除，此时退化成"覆盖写入"：
                # 旧 SDK 的多余文件会残留多占一点体积，但不影响运行
                failed.append("%s (%s)" % (child, e.strerror))
    if failed:
        print("  注意：有 %d 项旧资源没删掉，将在覆盖写入后残留：%s"
              % (len(failed), ", ".join(failed[:5])))

    for rel, data in assets.items():
        dst = os.path.join(ASSETS, rel.replace("/", os.sep))
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        with open(dst, "wb") as f:
            f.write(data)

    for name in kept:
        dst = os.path.join(ASSETS, name)
        if os.path.isdir(dst):
            shutil.rmtree(dst)
        shutil.move(os.path.join(keep_dir, name), dst)
    shutil.rmtree(keep_dir)
    print("assets -> 清掉旧 %d 项，写入 %d 个文件，保留 %s"
          % (removed, len(assets), sorted(kept) or "无"))


# ---------------------------------------------------------------- 官网合包通道

def download_bundle():
    os.makedirs(CACHE, exist_ok=True)
    zip_path = os.path.join(CACHE, os.path.basename(BUNDLE_URL))
    if os.path.exists(zip_path) and os.path.getsize(zip_path) > 100 * 1024 * 1024:
        print("复用缓存:", os.path.relpath(zip_path, ROOT))
        return zip_path
    print("下载官网整包（约 120MB）...")
    with urllib.request.urlopen(BUNDLE_URL, timeout=600) as r, open(zip_path, "wb") as f:
        shutil.copyfileobj(r, f)
    print("  -> %.1fMB" % (os.path.getsize(zip_path) / 1048576.0))
    return zip_path


def prepare_from_bundle(bundle):
    """bundle 可以是整包 zip、内层合包 zip，或已解压的目录。"""
    work = os.path.join(CACHE, "bundle")
    if os.path.isfile(bundle) and bundle.endswith(".zip"):
        if os.path.isdir(work):
            shutil.rmtree(work)
        os.makedirs(work)
        with zipfile.ZipFile(bundle) as z:
            if BUNDLE_INNER in z.namelist():
                inner = os.path.join(CACHE, BUNDLE_INNER)
                with open(inner, "wb") as f:
                    f.write(z.read(BUNDLE_INNER))
                with zipfile.ZipFile(inner) as z2:
                    z2.extractall(work)
            else:
                z.extractall(work)
        bundle = work

    jars = [n for n in os.listdir(bundle) if n.startswith("AMap3DMap_") and n.endswith(".jar")]
    if not jars:
        sys.exit("%s 下找不到 AMap3DMap_*.jar" % bundle)
    jar_name = jars[0]
    m = re.search(r"AMap3DMap_([0-9.]+)_AMapSearch_([0-9.]+)_AMapLocation_([0-9.]+)", jar_name)
    if not m:
        sys.exit("无法从文件名识别版本: " + jar_name)
    map_ver, search_ver, loc_ver = m.groups()
    print("高德 SDK：3D地图 %s / 搜索 %s / 定位 %s（官网合包）" % (map_ver, search_ver, loc_ver))

    classes, assets, natives = {}, {}, {}
    with zipfile.ZipFile(os.path.join(bundle, jar_name)) as z:
        for item in z.infolist():
            if item.is_dir():
                continue
            if item.filename.endswith(".class") or item.filename.startswith("META-INF/"):
                classes[item.filename] = z.read(item)
            elif item.filename.startswith("assets/"):
                assets[item.filename[len("assets/"):]] = z.read(item)

    for abi in ABIS:
        d = os.path.join(bundle, abi)
        if not os.path.isdir(d):
            sys.exit("合包缺少 %s 目录" % abi)
        natives[abi] = {n: open(os.path.join(d, n), "rb").read()
                        for n in os.listdir(d) if n.endswith(".so")}
    return map_ver, classes, assets, natives


# ---------------------------------------------------------------- Maven 通道

def download_maven(mirror, artifact, version):
    url = "%s/com/amap/api/%s/%s/%s-%s.jar" % (mirror.rstrip("/"), artifact, version, artifact, version)
    sys.stdout.write("下载 %s ... " % url)
    sys.stdout.flush()
    with urllib.request.urlopen(url, timeout=180) as r:
        data = r.read()
    print("%.1f MB" % (len(data) / 1048576.0))
    return data


def prepare_from_maven(mirror):
    print("注意：Maven 通道的 3dmap 最高只到 10.0.600，拿不到 V11，仅用于回退。")
    classes, assets, natives = {}, {}, {}
    for artifact, version in MAVEN_ARTIFACTS:
        data = download_maven(mirror, artifact, version)
        with zipfile.ZipFile(io.BytesIO(data)) as z:
            for name in z.namelist():
                if name.endswith("/"):
                    continue
                if name.startswith("lib/") and name.endswith(".so"):
                    abi, so = name[len("lib/"):].split("/", 1)
                    natives.setdefault(abi, {}).setdefault(so, z.read(name))
                elif name.startswith("assets/"):
                    assets.setdefault(name[len("assets/"):], z.read(name))
                elif name.endswith(".class") or name.startswith("META-INF/"):
                    classes.setdefault(name, z.read(name))  # 三者有重复类，先到先得
    return MAVEN_ARTIFACTS[0][1], classes, assets, natives


# ---------------------------------------------------------------- 入口

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--bundle", help="官网合包（zip 或已解压目录）；不给则自动下载整包")
    ap.add_argument("--maven", action="store_true", help="走 Maven 通道（仅能拿到 10.0.600）")
    ap.add_argument("--mirror", default="https://maven.aliyun.com/repository/public")
    args = ap.parse_args()

    os.makedirs(LIBS, exist_ok=True)
    if args.maven:
        map_ver, classes, assets, natives = prepare_from_maven(args.mirror)
    else:
        bundle = args.bundle or download_bundle()
        map_ver, classes, assets, natives = prepare_from_bundle(bundle)

    jar = os.path.join(LIBS, "amap-sdk-%s.jar" % map_ver)
    write_jar(jar, classes)
    drop_old_jars(jar)
    write_assets(assets)
    write_natives(natives)

    print("\n完成。app/build.gradle.kts 里的 jar 名应为 libs/%s" % os.path.basename(jar))


if __name__ == "__main__":
    main()
