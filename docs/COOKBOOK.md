# Pixel Lab Cookbook — Agent 配方手册

> 面向宿主 Agent（如 [Android-Guru-Agent](https://github.com/AceGuru-mjh/Android-Guru-Agent)）的实战剧本：每个配方给出目标、工具调用序列、预期输出与坑点。所有配方在 MCP 传输（SSE 或 WebSocket）与直接 Kotlin API 两种接入方式下等价。

## 目录

1. [照片 → 像素风 GIF](#1-照片--像素风-gif)
2. [程序化森林瓦片地图](#2-程序化森林瓦片地图)
3. [精灵图集 + Godot/Tiled 元数据](#3-精灵图集--godottiled-元数据)
4. [文字徽章 → 呼吸动画精灵表](#4-文字徽章--呼吸动画精灵表)
5. [QOI 无损往返工作流](#5-qoi-无损往返工作流)
6. [历史守护的批量编辑](#6-历史守护的批量编辑)
7. [Aseprite 文件导入 → 重着色 → 导出](#7-aseprite-文件导入--重着色--导出)
8. [等距瓦片地图渲染](#8-等距瓦片地图渲染)
9. [调色板统一：多素材色彩归一](#9-调色板统一多素材色彩归一)
10. [游戏图标一键导出（ICO 多尺寸）](#10-游戏图标一键导出ico-多尺寸)
11. [流水线配方：可复用的转换预设](#11-流水线配方可复用的转换预设)
12. [握手 WebSocket 传输](#12-握手-websocket-传输)

---

## 1. 照片 → 像素风 GIF

**目标**：用户提供一张照片，产出 64×64、16 色、Floyd-Steinberg 抖动的循环 GIF。

```
1. io_import_frames(data_b64=<photo>)
   → format=PNG, width=480, height=640, palette_hint=null   # 照片色数 > 256
2. canvas_create(width=64, height=64, palette_id="pico-8")   # 或 endesga-32
3. convert_image(pixels=<采样>, width=64, height=64, color_count=16, dither="floydsteinberg")
   → 量化+抖动一次完成
4. canvas_outline(color="#000000")                            # 1px 黑描边增强轮廓
5. anim_breathe(amplitude=1)                                  # 可选：两帧微呼吸
6. export_gif(loop_count=0)                                   → byte_count
```

**坑点**：
- `convert_image` 的 `pixels` 是 ARGB 整数数组（行主序）；大图先缩后传，别传整张 4K 照片的像素数组。
- 照片没有 `palette_hint`（超 256 色），务必显式指定 `palette_id`。
- 描边放在量化之后，否则描边色也会被量化吞掉。

## 2. 程序化森林瓦片地图

**目标**：零美术资产，生成 8×8 单元格草地地形图并渲染 PNG。

```
1. tilemap_create(rows=["01001101", "11111111", ...])         # '1'=草地
   → map_id="map-1"
2. tilemap_autotile(map_id="map-1", terrain_tag=1, mode="blob47")
   → 47 tile 完整形态（边缘圆润）
3. tilemap_render(map_id="map-1", scale=4)
   → data_b64（PNG 魔数开头）
```

**坑点**：
- `mode="simple16"` 只需 16 形态，瓦片集更小但边缘更硬；`wang` 用于 Wang 角点风格。
- 想要石块风格：`tilemap_create` 的 `tileset="stone-wang16"`。
- `height_offsets` 只在 `tilemap_iso_render` 里有效，平面渲染忽略它。

## 3. 精灵图集 + Godot/Tiled 元数据

**目标**：把会话里的多帧精灵打包成一张图集 + 引擎可直接读取的区域文件。

```
1. gen_sprite(type="tree", size=16, seed=1)
2. frame_clone(frame_index=0)                  # 复制变体
3. io_export_atlas(format="godot4", padding=1, extrude=1, trim=true)
   → image PNG data_b64 + regions JSON 文本
```

**坑点**：
- `extrude=1` 把边缘像素向外复制一圈——**必须开**，否则采样渗色；它已计入 pack 预留环，不会互相污染。
- `trim=true` 时每个区域带 `original`（原始尺寸+偏移），动画引擎要用它还原锚点。
- 五种 format 的区域坐标系都是图集左上原点、像素单位；Godot3/4 的数组字段顺序不同，别混用。

## 4. 文字徽章 → 呼吸动画精灵表

**目标**：带描边+阴影的 "HP+10" 浮动徽章，导出两帧精灵表。

```
1. text_generate(text="HP+10", color="#00E436", font="8x8", scale=1)
2. template_apply(template_id="...") 或手绘
   # 文字特效路径（PR5 引擎）：
   # TextStyler(TextOptions(outline=1, shadow=Dx1Dy1)) → 描边/阴影/发光层栈
3. anim_breathe(amplitude=1)                   → 两帧
4. export_spritesheet(layout="H", scale=1)     → byte_count
```

**坑点**：
- `text_generate` 返回的是尺寸+像素数，精灵内容在会话里；先 `canvas_info` 确认非零像素数 > 0 再导出。
- 精灵表横向拼接时两帧高度必须一致——呼吸动画只位移不改尺寸。

## 5. QOI 无损往返工作流

**目标**：像素图在工具间无损中转（QOI 比 PNG 编码快、无压缩陷阱）。

```
1. io_export_qoi(frame_index=0)                → data_b64
2. （任意外部工具处理后）
3. io_import_frames(data_b64=<qoi>)
   → format=QOI, width/height 与导出一致, frames=1
```

**坑点**：
- QOI 无 alpha 通道标志——3/4 通道由文件头决定，往返后透明像素保持 0。
- QOI 是**逐像素状态编码**：任何一字节损坏都会导致后续全部错位，导入会给出精确偏移的错误信息。

## 6. 历史守护的批量编辑

**目标**：Agent 连续执行 N 步编辑，失败时整体回滚到已知好状态。

```
1. gen_texture(type="stone", width=32, height=32, seed=42)
   → undo_depth=1                              # 创建本身可撤销
2. shade(light_angle=315, strength=160)         → undo_depth=2
3. outline(color="#001030")                     → undo_depth=3
4. history_status                               → entries=[gen_texture, shade, outline], modified=true
5.（发现效果不对）
   history_undo × N → 逐级回退；或按需只撤 outline
6. 满意后 history_mark_saved                    → modified=false
```

**坑点**：
- V3 历史按**会话**隔离；`project_undo`（V1 引擎快照栈）是另一套，不要混用两套撤销。
- `history_undo` 有 current 守卫：如果项目被 V1 工具改过（会话与历史不同步），撤销返回 null 语义（工具报错）而不是错误回退。

## 7. Aseprite 文件导入 → 重着色 → 导出

**目标**：读入用户的 .ase 文件，保留图层/帧/标签结构，改调色板后导出 GIF。

```
1. io_import_frames(data_b64=<ase>)            → format=ASE, frames=N, palette_hint=[...]
   # 完整项目路径：
   io_import_project(data_b64=<ase>)           → 图层/帧/标签/cel 全保留
   # AsepriteImporter 直连（Kotlin API）：
   # AsepriteImporter.import(bytes, "name") → SpriteProject
2. palette_switch(palette_id="pico-8") + convert_image(color_count=16)
   # 或 replace_color 逐色替换
3. export_gif(loop_count=0)
```

**坑点**：
- `.ase` 的 cel 透明度会烘焙进像素 alpha（模型无 cel 级 opacity 字段）。
- 图层组（folder layer）被展平——导入后图层是平铺列表，组内 cel 保留。
- 链接 cel（linked）解析为像素拷贝；16 位灰度旧文件按 v>>8 提亮。

## 8. 等距瓦片地图渲染

**目标**：把地形图渲染成菱形等距视角，带行级抬升。

```
1. tilemap_create(rows=["1111", "1001", ...])  → map_id
2. tilemap_autotile(map_id, terrain_tag=1)
3. tilemap_iso_render(map_id, tile_w=32, tile_h=16, height_offsets=[0, 0, -8, -16])
   → data_b64（PNG）
```

**坑点**：
- `height_offsets` 长度必须等于地图行数；负值=抬升。
- 等距画布宽 = (w+h)·tileW/2——大地图乘 32 位尺寸上限前先估算。
- `iso_diamond_mask(tile_w, tile_h)` 给出美术需要对齐的菱形蒙版形状。

## 9. 调色板统一：多素材色彩归一

**目标**：不同来源的精灵统一到 PICO-8 调色板。

```
1. io_import_frames(data_b64=<spriteA>)        → palette_hint
2. palette_switch(palette_id="pico-8")
3. convert_image(color_count=16)               # Lab 最近邻映射
4. 对每个素材重复
5. io_export_atlas(format="generic")           # 统一色板后打包
```

**坑点**：
- 色彩映射用 **CIELAB 距离**（感知均匀），别自己用 RGB 欧氏距离再映射一遍。
- `ramp(base_color, steps=5)` 可为统一后的调色板补明暗阶。

## 10. 游戏图标一键导出（ICO 多尺寸）

**目标**：会话精灵直接产出 Windows ICO（16/32/48 嵌入 PNG）。

```
1. gen_sprite(type="gem", size=32, seed=7)
   # 或任意现有会话
2. io_export_ico(sizes=[16, 32, 48])
   → data_b64（ICONDIR + 3 个 PNG 内嵌条目）
```

**坑点**：
- ICO 内嵌的是完整 PNG 帧（最近邻降采样，像素风不糊）；256 尺寸条目的宽度字节为 0（规范规定）。
- 透明像素保持透明——先 `texture_trim` + `canvas_resize(anchor="center")` 让主体居中再导。

## 11. 流水线配方：可复用的转换预设

**目标**：把"缩放→量化→抖动→描边"固化为一份 JSON，一条调用复用。

```
1. pipeline_recipe_validate(recipe_json=...)   → steps 摘要（先校验）
2. pipeline_run(recipe_json=...)
   → 逐步执行，结果项目写回会话
```

配方示例：

```json
{"steps":[
  {"type":"scale","factor":2},
  {"type":"quantize","algorithm":"mediancut","maxColors":16},
  {"type":"dither","algorithm":"floydsteinberg"},
  {"type":"palette-map","palette":"pico-8"},
  {"type":"outline","color":"#FF000000","mode":"outer","connectivity":8},
  {"type":"bg-remove","tolerance":40}
]}
```

**坑点**：
- 步骤顺序敏感：先 scale 后量化（小图量化更快更稳）；outline 永远最后。
- 未知 type / 非法参数在 `validate` 阶段就报错（带位置信息），`run` 不会半途留下脏项目。

## 12. 握手 WebSocket 传输

**目标**：宿主不走 SSE，用长连接 WebSocket 承载 JSON-RPC。

```
GET /mcp HTTP/1.1
Upgrade: websocket
Connection: Upgrade
Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==
Sec-WebSocket-Version: 13

→ 101 Switching Protocols
  Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xUo=
```

随后每个 WebSocket 文本消息 = 一条 JSON-RPC 请求，回复 = 一条文本帧（>64KB 自动分片）。

**坑点**：
- 客户端帧**必须掩码**（RFC 6455 §5.1），未掩码立即收到 close 1002。
- 单消息上限 1MB（超限 close 1009）；ping 自动回 pong。
- 服务器 close 帧先于 TCP 断开——正常关闭应答 close 1000 再断。

---

## 接入速查

| 场景 | 方式 |
|:---|:---|
| Android 宿主引库 | `pixel-core`（纯 JVM）+ 可选 `pixel-ui-compose` |
| Agent 工具化 | `pixel-mcp`：SSE 或 WebSocket 传输，120 个工具 |
| 持久化 | `ProjectStore(rootDir)`：原子写 + LRU 缓存 + 缩略图 |
| 直接 Kotlin | `PixelLab.create()` 门面 + 各包公共 API |
