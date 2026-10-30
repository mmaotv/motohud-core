# -*- coding: utf-8 -*-
"""生成 dialog_settings.xml：分类分组 + 可滚动。改设置项时改这里的 ROWS/SECTIONS 再重跑。"""
import io

HEAD = '''<?xml version="1.0" encoding="utf-8"?>
<!--
  设置面板（底部弹出）—— 所有「可以调的东西」的唯一入口。

  ## 为什么是 NestedScrollView 作根

  这里曾经是一个普通 LinearLayout，条目一路涨到 20 行（约 1000dp），
  而竖屏只有约 806dp、横屏仅 360dp —— 超出的部分直接看不到也够不着。
  BottomSheet 的标准解法就是把根换成 NestedScrollView：内容高时面板顶到屏幕上限、
  内部自己滚动，而不是被裁掉。

  ## 分类原则

  按「用户想干什么」分组，不按代码模块分组：
    连接与投屏 —— 车机连不连、投不投
    语音播报   —— 说什么、说多细、哪些别说
    地图显示   —— 地图长什么样、吃多少性能
    路线与导航 —— 怎么算路、避开什么
    数据与系统 —— Key、隐私、缓存、关于

  ## 为什么开关这么多

  这些以前全是写死在代码里的常量，或者散落在导航页的临时对话框里。
  骑手场景差别极大（跑长途 vs 城区通勤、白天强光 vs 夜骑、高端机 vs 老旧机），
  写死就等于替用户做决定。宁可设置项多一点，也不要「这功能我用不上还没法关」。
-->
<androidx.core.widget.NestedScrollView xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:background="@drawable/bg_bottom_card"
    android:fillViewport="true">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:paddingStart="20dp"
        android:paddingTop="10dp"
        android:paddingEnd="20dp"
        android:paddingBottom="24dp">

        <View
            android:layout_width="36dp"
            android:layout_height="4dp"
            android:layout_gravity="center_horizontal"
            android:layout_marginBottom="14dp"
            android:background="@drawable/bg_grabber" />

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:fontFamily="@font/inter"
            android:text="@string/settings_title"
            android:textColor="@color/text_primary"
            android:textSize="20sp"
            android:textStyle="bold" />
'''

DIV = '''
                <View
                    android:layout_width="match_parent"
                    android:layout_height="1dp"
                    android:background="@color/divider" />
'''


def section(title):
    return '''
        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="18dp"
            android:layout_marginBottom="6dp"
            android:letterSpacing="0.04"
            android:paddingStart="6dp"
            android:text="@string/%s"
            android:textColor="@color/text_tertiary"
            android:textSize="12sp"
            android:textStyle="bold" />
''' % title


def card_open():
    return '''
        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:background="@drawable/bg_card"
            android:orientation="vertical"
            android:paddingStart="14dp"
            android:paddingEnd="10dp">
'''


CARD_CLOSE = '''
        </LinearLayout>
'''


def row_switch(id_, title, desc, first=False):
    out = '' if first else DIV
    # 高度用 wrap_content + minHeight 而不是固定 52dp：
    # 说明文字在窄屏/长文案下会折成两行，固定高度会让第二行压到分隔线上。
    out += '''
                <LinearLayout
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:minHeight="52dp"
                    android:gravity="center_vertical"
                    android:orientation="horizontal"
                    android:paddingTop="9dp"
                    android:paddingBottom="9dp">

                    <LinearLayout
                        android:layout_width="0dp"
                        android:layout_height="wrap_content"
                        android:layout_weight="1"
                        android:orientation="vertical">

                        <TextView
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"
                            android:text="@string/%s"
                            android:textColor="@color/text_primary"
                            android:textSize="14sp" />

                        <TextView
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"
                            android:layout_marginTop="1dp"
                            android:text="@string/%s"
                            android:textColor="@color/text_tertiary"
                            android:textSize="11sp" />
                    </LinearLayout>

                    <com.google.android.material.materialswitch.MaterialSwitch
                        android:id="@+id/%s"
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content" />
                </LinearLayout>
''' % (title, desc, id_)
    return out


def row_jump(id_, title, desc=None, value_id=None, first=False):
    """跳转行。desc / value 都可省略；value 显示在右侧（如「详细」「引擎占用」）。"""
    out = '' if first else DIV
    if desc:
        left = '''
                    <LinearLayout
                        android:layout_width="0dp"
                        android:layout_height="wrap_content"
                        android:layout_weight="1"
                        android:orientation="vertical">

                        <TextView
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"
                            android:text="@string/%s"
                            android:textColor="@color/text_primary"
                            android:textSize="14sp" />

                        <TextView
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"
                            android:layout_marginTop="1dp"
                            android:text="@string/%s"
                            android:textColor="@color/text_tertiary"
                            android:textSize="11sp" />
                    </LinearLayout>
''' % (title, desc)
        h = 64
    else:
        left = '''
                    <TextView
                        android:layout_width="0dp"
                        android:layout_height="wrap_content"
                        android:layout_weight="1"
                        android:text="@string/%s"
                        android:textColor="@color/text_primary"
                        android:textSize="14sp" />
''' % title
        h = 48
    if value_id:
        right = '''
                    <TextView
                        android:id="@+id/%s"
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:textColor="@color/text_tertiary"
                        android:textSize="12sp" />
''' % value_id
    else:
        right = ''
    out += '''
                <LinearLayout
                    android:id="@+id/%s"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:minHeight="%ddp"
                    android:background="?attr/selectableItemBackground"
                    android:gravity="center_vertical"
                    android:orientation="horizontal"
                    android:paddingTop="4dp"
                    android:paddingBottom="4dp">
%s%s                </LinearLayout>
''' % (id_, h, left, right)
    return out


SECTIONS = [
    ('settings_section_connection', [
        ('sw', 'sw_auto_connect', 'device_card_auto_connect', 'settings_auto_connect_desc'),
        ('sw', 'sw_cast', 'navi_cast_switch_title', 'navi_cast_switch_desc'),
        ('sw', 'sw_screen_off', 'hud_screen_off', 'navi_screen_off_desc'),
    ]),
    ('settings_section_voice', [
        ('sw', 'sw_voice', 'settings_voice', 'settings_voice_desc'),
        ('jump', 'set_voice_mode', 'settings_voice_mode_title', None, 'tv_voice_mode_value'),
        ('jump', 'set_voice_engine', 'settings_voice_engine', None, 'set_voice_engine_state'),
        ('jump', 'set_preset_voice', 'settings_preset_voice_title', None, 'tv_preset_voice_value'),
        ('sw', 'sw_cue_road', 'settings_cue_road_title', 'settings_cue_road_desc'),
        ('sw', 'sw_cue_curve', 'settings_cue_curve_title', 'settings_cue_curve_desc'),
        ('sw', 'sw_cue_terrain', 'settings_cue_terrain_title', 'settings_cue_terrain_desc'),
        ('sw', 'sw_cue_harsh', 'settings_cue_harsh_title', 'settings_cue_harsh_desc'),
    ]),
    ('settings_map_section', [
        ('sw', 'sw_traffic', 'settings_traffic_layer', 'settings_traffic_layer_desc'),
        ('jump', 'set_map_theme', 'settings_map_theme_title', None, 'tv_map_theme_value'),
        ('jump', 'set_offline_map', 'settings_offline_map_title', 'settings_offline_map_desc', None),
        ('sw', 'sw_map_3d', 'settings_map_3d_title', 'settings_map_3d_desc'),
        ('sw', 'sw_map_text', 'settings_map_text_title', 'settings_map_text_desc'),
        ('sw', 'sw_route_reduce', 'settings_route_reduce_title', 'settings_route_reduce_desc'),
        ('sw', 'sw_screen_keep_on', 'settings_screen_keep_on_title', 'settings_screen_keep_on_desc'),
        ('jump', 'set_screen_bright', 'settings_screen_bright_title', 'settings_screen_bright_desc', 'tv_screen_bright_value'),
    ]),
    ('settings_section_route', [
        ('jump', 'set_route_prefs', 'settings_route_prefs_title', 'settings_route_prefs_desc', None),
        ('sw', 'sw_avoid_ferry', 'settings_avoid_ferry_title', 'settings_avoid_ferry_desc'),
        ('jump', 'set_avoid_zone', 'avoid_zone_title', None, None),
        ('jump', 'set_amap_key', 'settings_amap_key', None, None),
        ('jump', 'set_privacy', 'settings_privacy', None, None),
    ]),
    ('settings_section_storage', [
        ('jump', 'set_crash_log', 'settings_crash_log_title', 'settings_crash_log_desc', 'tv_crash_state'),
        ('jump', 'set_clear_cache', 'settings_clear_cache', None, None),
        ('jump', 'set_onboarding', 'settings_onboarding', None, None),
        ('jump', 'set_about', 'settings_about', None, None),
    ]),
]


def build():
    parts = [HEAD]
    for sec_title, rows in SECTIONS:
        parts.append(section(sec_title))
        parts.append(card_open())
        for i, row in enumerate(rows):
            first = (i == 0)
            if row[0] == 'sw':
                parts.append(row_switch(row[1], row[2], row[3], first=first))
            else:
                parts.append(row_jump(row[1], row[2], row[3], row[4], first=first))
        parts.append(CARD_CLOSE)
    parts.append('''
    </LinearLayout>
</androidx.core.widget.NestedScrollView>
''')
    return ''.join(parts)


if __name__ == '__main__':
    text = build()
    # 统一 UTF-8 + LF，避免 Windows 下 CRLF 混入造成 diff 噪声
    io.open('app/src/main/res/layout/dialog_settings.xml', 'w', encoding='utf-8', newline='\n').write(text)
    print('written lines =', text.count('\n'))
