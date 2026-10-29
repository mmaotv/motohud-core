# -*- coding: utf-8 -*-
"""从高德 libamaptbt.so 提取内置的播报调度参数配置(JSON)。"""
import zipfile, re, json

APK = r'D:\APPDEV\tmp_apk\amap.apk'
SO = 'lib/arm64-v8a/libamaptbt.so'
OUT = r'D:\APPDEV\mozheng\amap_tts_config.json'

BS = chr(92)  # 反斜杠


def extract_json(s, start, limit=400000):
    depth = 0
    in_str = False
    esc = False
    for i in range(start, min(len(s), start + limit)):
        c = s[i]
        if in_str:
            if esc:
                esc = False
            elif c == BS:
                esc = True
            elif c == '"':
                in_str = False
            continue
        if c == '"':
            in_str = True
        elif c == '{':
            depth += 1
        elif c == '}':
            depth -= 1
            if depth == 0:
                return s[start:i + 1]
    return None


z = zipfile.ZipFile(APK)
txt = z.read(SO).decode('utf-8', 'ignore')

best = None
for m in re.finditer(r'\{"mixfork0"', txt):
    j = extract_json(txt, m.start())
    if j and (best is None or len(j) > len(best)):
        best = j

print('提取长度:', len(best) if best else 0)
if best:
    try:
        obj = json.loads(best)
        with open(OUT, 'w', encoding='utf-8') as f:
            json.dump(obj, f, ensure_ascii=False, indent=2)
        print('顶层 key 数:', len(obj))
        for k in obj.keys():
            print('  -', k)
        print('已保存:', OUT)
    except Exception as e:
        print('parse fail:', e)
        with open(OUT, 'w', encoding='utf-8') as f:
            f.write(best)
        print('raw 已保存:', OUT)
