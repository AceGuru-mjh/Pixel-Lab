# MCP Tools — 183 个工具参考（tier 1–6 全量）

传输：JSON-RPC 2.0，`GET /sse`（事件流）+ `POST /messages`（请求/响应并镜像至 SSE）；可选 RFC 6455 WebSocket 通道（`start(port, websocketPort)`）。请求体支持 Content-Length 与 chunked。
会话：`session_id` 参数（缺省自动创建，LRU 32 上限；生成类工具无 `session_id` 时自动新建；id ≤ 128 字符）。
错误形状：参数错误 `-32602` / 工具崩溃 `isError` / 未知工具 `-32601`。
图像纪律：导出工具返回 `byte_count` 与摘要，≤ 2MB 时同时内联 `data_b64`（超出则提示改用 session_save / 缩小 scale）；V3 的 io_export_* 同样遵循。
派发：`McpToolRouter` 层级链（tier 1 canvas/draw/layer/frame/palette/anim/text/template/export/project 58 个 → tier 2 形状/画笔/选区/对称/样式/文字 36 个 → tier 3 io/生成/图集/tilemap/管线 31 个 → tier 4 分析/变换/色彩/矢量 33 个 → tier 5 视觉 11 个 → tier 6 工效学 14 个），`tools/list` 每条带 `tier` 字段。

## 画布尺寸纪律

`canvas_create`/`project_new` 边长限制 `1..8192`；几何跨度超过 262144 步的线段直接拒绝（防 DoS）。

## canvas_*（8）

| 工具 | 参数 | 说明 |
|:---|:---|:---|
| `canvas_create` | width, height, session_id?, palette_id? | 新建画布项目 |
| `canvas_info` | session_id? | 尺寸/帧数/图层数/活跃层/调色板/非零像素数 |
| `canvas_clear` | session_id? | 清空活动 cel |
| `canvas_shift` | dx, dy | 整体位移 |
| `canvas_flip_h` | — | 水平翻转活动 cel |
| `canvas_flip_v` | — | 垂直翻转活动 cel |
| `canvas_rotate` | times | 90°×times（方形画布） |
| `canvas_outline` | color | 不透明像素 4 邻域描边 |

## draw_*（10）

`draw_pixel`(x,y,color) · `draw_line`(x0,y0,x1,y1,color,thickness?) · `draw_rect`(x,y,w,h,color,filled?) · `draw_circle`(cx,cy,r,color,filled?) · `draw_pixels`(points[],color) · `draw_stroke`(points[],color,thickness?) · `fill`(x,y,color,tolerance?) · `pick_color`(x,y) · `replace_color`(from,to,tolerance?) · `erase_pixels`(points[])

## layer_*（9）

`layer_add`(name?) · `layer_remove`(layer_id) · `layer_rename`(layer_id,name) · `layer_move`(layer_id,to_index) · `layer_set_opacity`(layer_id,opacity) · `layer_set_visible`(layer_id,visible) · `layer_set_locked`(layer_id,locked) · `layer_list`() · `layer_set_active`(layer_id) → 设定绘图目标图层（必须为现存图层 id，见 layer_list）

## frame_*（7）

`frame_add`(after_index?) · `frame_clone`(frame_index) · `frame_delete`(frame_index) · `frame_move`(from,to) · `frame_set_duration`(frame_index,duration_ms|null) · `frame_list`() · `frame_set_active`(frame_index) → 设定编辑帧（draw/批量类工具的默认落点；draw_batch 前先调它）

## palette_*（4）

`palette_list`（6 内置：pico-8/gameboy/nes54/endesga-32/clawd/kimi）· `palette_switch`(palette_id) · `palette_set_colors`(colors[]: ARGB int 或 "#RRGGBB" 混排，去重保序，≤256；palette_id?="custom", name?) → 直接用颜色清单替换会话调色板 · `palette_closest_color`(color→Lab 最近邻索引+hex)

## convert_*（3）

`convert_image`(pixels[]|说明, width, height, target_w?, target_h?, color_count?, dither?, session_id?, commit?=true) → 量化+抖动；传 `session_id` 时把转换结果盖到该会话活动 cel（返回 placed/clipped 与 projectSummary，`commit=false` 只转换不落盘） · `convert_analyze`(同输入→建议尺寸/色数/调色板) · `convert_refine`(snap_alpha?, remove_stray?)

## anim_*（4）

`anim_set_fps`(fps) · `anim_tag`(name,start,end) · `anim_breathe`(amplitude?) · `anim_preview_count`()

## text_* / template_*（3 + 1 借驻）

`text_generate`(text, color, font?="5x7", scale?≤16, spacing?≤64) → 尺寸+非零像素数 · `draw_text`（**tier-2 注册**，语义见 V2 节）(session_id, text, x?, y?, color?, font?, scale?≤16, spacing?≤64, outline_color?, glow_color?, shadow_color?, vertical?) → 渲染后**盖印到会话活动 cel**（非透明文字像素覆盖，越界裁剪并返回 placed/clipped）——补齐 text_generate 只报尺寸不落笔的断链 · `template_list`（6 模板）· `template_apply`(template_id)

## export_*（5）

`export_png`(scale?) · `export_spritesheet`(layout?, columns?, scale?, margin?) · `export_gif`(loop_count?, dither?) · `export_apng`(loop_count?) · `export_codex_pet`(pet_name?, tracks?) — 全部返回 `byte_count`，≤ 2MB 时附 `data_b64`（超出时给 note 提示 session_save / io_export_* + 小 scale）

## project_*（5）

`project_new`(width,height,palette_id?) · `project_state` · `project_list`（会话目录）· `project_undo` · `project_redo`

## JSON-RPC 示例

```json
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}
{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"draw_pixel","arguments":{"x":3,"y":4,"color":"#ff8800"}}}
```

响应：`{"jsonrpc":"2.0","id":2,"result":{"content":[{"type":"text","text":"{\"ok\":true,...}"}]}}`

## V2（36 个）— 形状/画笔/选区/变换/混合/对称/效果/扩展调色板/样式文字/模板 2/项目序列化

> 补遗：此前该参考声称 tier 1–6 全量却漏掉了整个 V2 节；本节补齐。V2 所有变更工具与 V1 共享同一引擎（同一条 undo 历史），`session_id` 语义与其他 tier 一致。

### v2-shape（6）

`shape_line`(session_id, x0, y0, x1, y1, color, thickness?1..16) → 粗 Bresenham 线 · `shape_rect`(session_id, x, y, width, height, color, filled?) → 矩形（描边/填充） · `shape_ellipse`(session_id, cx, cy, rx, ry, color, filled?) → 椭圆 · `shape_polygon`(session_id, points[], color, filled?) → 闭合多边形（顶点表，扫描线填充） · `shape_star`(session_id, cx, cy, tips, r_outer, r_inner, color, filled?) → 星形 · `shape_bezier`(session_id, points[3|4], color, steps?) → 二次/三次贝塞尔

### v2-brush（1）

`brush_draw`(session_id, points[], color, size?1..8, shape?square/round, noise?, symmetry?) → 带对称与像素完美细化的笔触

### v2-selection（6）

`selection_extract`(session_id, x, y, w, h) → 提取区域为独立像素数组 · `selection_move`(session_id, x, y, w, h, dx, dy) → 平移所选像素（洞补透明） · `selection_flip_h` / `selection_flip_v`(同参数) → 区域镜像 · `selection_rotate`(session_id, x, y, size, quarter?) → 方形区域 90° 旋转 · `selection_magic`(session_id, x, y, tolerance?) → 魔棒（泛洪包围盒）

### v2-transform（7）

`transform_rotate`(session_id, quarter) → cel 90° 倍数旋转（画布随之） · `transform_scale`(session_id, factor) → 整数倍最近邻缩放 · `transform_grow`(session_id, color, steps?) → 不透明域膨胀 · `transform_shrink`(session_id, steps?) → 边界腐蚀 · `transform_crop`(session_id, x, y, w, h) → 裁剪 cel+画布 · `transform_pad`(session_id, l, t, r, b) → 透明衬边

### v2-blend / symmetry / effect（3）

`blend_apply`(session_id, mode) → 把当前层按混合模式烘焙到其下层合成之上 · `symmetry_reflect_preview`(points[], symmetry) → 只读对称反射预览 · `effect_apply`(session_id, name, 参数…) + `effect_list` → 程序化动画效果（名称+定位参数）

### v2-palette（6）

`palette_library_list` → 扩展调色板库（21 套，含 hex） · `palette_harmony`(base, scheme?complementary/triadic/...) → 和谐色板 · `palette_gradient`(from, to, steps) → CIELAB 感知渐变 · `palette_export_jasc` / `palette_export_gpl` / `palette_export_hex` → 会话或库调色板导出为 JASC-PAL / GIMP .gpl / 十六进制表

### v2-text（2 + 1）

`draw_text`(session_id, text, x?, y?, color?, font?5x7/8x8, scale?≤16, spacing?≤64, outline_color?, glow_color?, shadow_color?, vertical?) → 样式文字盖印到活动 cel（placed/clipped） · `text_style_render`(text, 样式参数) → 只渲染不落笔（返回尺寸+亮像素）

### v2-template / project / export（5）

`template_v2_list` → V2 模板目录（mushroom/slime/ghost/…） · `template_v2_apply`(template_id, palette_id?, session_id?) → 实例化到会话（**每次铸造全新 project id**，不与他会话共享 undo） · `project_save`(session_id) → 序列化 v2 项目 JSON（>4k 字符只回摘要） · `project_load`(json, session_id?) → 重建会话（同样铸造全新 id） · `export_aseprite_json`(session_id) → Aseprite spritesheet 元数据 JSON

## V3 — 导入/生成/瓦片地图/图集/历史/流水线（31 个新工具，v3 传输含 WebSocket）

V3 注册表与 V1/V2 并列挂载（`McpToolRegistryV3(lab)`），新增 `transport/WebSocketServer.kt`：手写 RFC 6455 WebSocket（SHA-1 握手、帧编解码、分片、ping/pong、掩码强制校验），与 SSE 传输共用 JSON-RPC 核心。

### v3-io（8）

| 工具 | 参数 | 说明 |
|:---|:---|:---|
| `io_import_frames` | data_b64 | 魔数嗅探 PNG/APNG/GIF/QOI/BMP/ASE → 格式/尺寸/帧数/延迟/调色板提示 |
| `io_import_project` | data_b64, name? | 项目 JSON（v2 线格式）→ 会话项目 |
| `io_import_image` | data_b64, name?, session_id? | 图片字节（PNG/APNG/GIF/QOI/BMP/ASE）→ 完整会话项目（GIF/APNG 帧时长与调色板提示保留；无 session_id 新建会话，有则覆写该会话）——导出的反通道，字节变回可编辑画布 |
| `palette_import` | text, format?(jasc/gpl/hex 自动嗅探), session_id?, palette_id? | 调色板文本解析（JASC-PAL / GIMP .gpl / hex 列表，≤64KB）；有 session_id 则应用为会话调色板，无则只返回解析结果（applied=false） |
| `io_export_qoi` | frame_index?, session_id? | 合成帧 → QOI 1.0 字节（≤ 2MB 附 data_b64） |
| `io_export_bmp` | frame_index?, session_id? | 合成帧 → 32 位 BMP（≤ 2MB 附 data_b64） |
| `io_export_ico` | sizes?, session_id? | 合成帧 → 多分辨率 ICO（默认 [16,32,48]） |
| `io_export_atlas` | padding?, extrude?, trim?, format?, scale? | 全帧打包图集 PNG + 引擎元数据（generic/godot3/godot4/tiled/phaser） |

### v3-gen（10）

`gen_texture`(type: clouds/wood/stone/marble/bricks/checker, width, height, palette_id?|colors[], seed?, rings?/veins?/brick_w?/brick_h?/mortar?/cell?) · `gen_sprite`(type: tree/rock/gem/ship, size?|width/height, colors?, seed?) · `gen_noise_field`(width, height, seed, tile?, octaves?) · `gen_silhouette`(color) · `outline`(color, mode: outer/inner, connectivity: 4/8) · `shade`(light_angle?, strength?, dark_color?, light_color?) · `ramp`(base_color, steps, lighten?, darken?) → 纯函数 hex 列表 · `flip_rotate`(op: fliph/flipv/rot90cw/rot90ccw/rot180) · `canvas_resize`(width, height, anchor: center/corner) · `project_thumbnail`(frame_index?, max_size?≤64)

### v3-map（5）

`tilemap_create`(rows[]: "01" 字符串, width?, height?, tile_size?=8, map_id?, session_id?) → map_id（瓦片集固定为草地 blob-47） · `tilemap_autotile`(map_id, terrain_tag, mode: blob47/simple16/wang) · `tilemap_render`(map_id, scale?) → PNG · `tilemap_iso_render`(map_id, tile_w?, tile_h?, height_offsets[]?) → PNG · `iso_diamond_mask`(tile_w, tile_h) → PNG

### v3-atlas（2）

`texture_trim`(minimum?) → 偏移量 · `texture_extrude`(amount) → 抗缝隙外扩

### v3-history（4）

`history_status` → 深度/条目标签/脏标记 · `history_undo` · `history_redo` · `history_mark_saved` — 每会话独立命令历史，创建/编辑/生成全部可撤销

### v3-pipeline（2）

`pipeline_run`(recipe_json) → 步骤顺序执行（scale/quantize/dither/palette-map/outline/trim/posterize/bg-remove） · `pipeline_recipe_validate`(recipe_json) → 步骤摘要（纯校验）

## V4（33 个）— 第 4 轮能力（分析 / 变换 / 世界生成 / 色彩科学 / 矢量）

### v4-analysis（11）

`frame_histogram`(session_id) → R/G/B/A/Rec.709 luma 通道统计（均值/方差/熵/分位数/透明比） · `frame_otsu`(session_id, levels, binarize) → Otsu 单级/多级（DP）阈值，可选二值化 · `frame_equalize`(session_id) → 保色相 luma 直方图均衡 · `frame_autolevels`(session_id, low_pct, high_pct) → 百分位对比度拉伸 · `frame_convolve`(session_id, kernel[13 种], edge[clamp/wrap/transparent], alpha[premultiplied/straight/alpha_only]) → 卷积 · `frame_morphology`(session_id, op[8 种], element[square3/cross3/square5]) → 膨胀/腐蚀/开闭/去孤点/填洞/描边/清理 · `frame_components`(session_id, connectivity, mode) → 连通域清单（面积/包围盒/周长/洞/边界） · `frame_metrics`(session_id, frame_index_b, frame_index_a, tolerance) → MAE/PSNR/差异比/差异框 · `frame_audit`(session_id, tiny_cluster_area, similar_tolerance) → 像素工艺审计（尘点/断角/棋盘/洞/微簇/锯齿）+ 0-100 评分 + 建议 · `frame_heal`(session_id, tiny_cluster_area?, similar_tolerance?, fix_*?, require_no_regression?) → 审计驱动修复：清尘/去微簇/填洞/桥接断角与棋盘后重审计并落盘（净负分自动回滚），返回 score_before/after/delta、fixed 各规则计数与 remaining_findings —— audit→heal 一调用闭环 · `frame_remove_small`(session_id, min_area, connectivity) → 按面积去斑

### v4-transform（6）

`frame_rotate`(session_id, degrees, bounds[expand/crop]) → RotSprite 三次剪切任意角度（crop 原位 / expand 新项目） · `frame_scale2x`(session_id, factor[2/3], variant[plain/corners]) → AdvMAME Scale2x/3x 新项目 · `frame_epx`(session_id, factor[2/3]) → EPX 整数放大新项目 · `frame_xbr`(session_id) → xBR2x 新项目 · `frame_resample`(session_id, width, height, mode[nearest/box]) → 半像素中心重采样新项目 · `frame_mipmap`(session_id, max_levels) → 只读金字塔（尺寸 + 每级 PNG b64）

### v4-worldgen（6）

`gen_worley`(session_id, width, height, seed, feature[f1/f2/border/cell], cell_size, contrast) → Worley 噪声新项目 · `gen_dungeon`(session_id, width, height, seed, min_leaf) → BSP 地牢（房间/走廊/门）新项目 · `gen_cave`(session_id, width, height, seed, fill_chance, smooth_passes, walk_tiles) → 元胞自动机洞穴新项目 · `gen_biome`(session_id, width, height, seed, theme[overworld/volcanic/frozen], height_scale, moisture_scale) → 生物群系世界图新项目 · `gen_lsystem`(session_id, preset[bush/tree/koch/fern/custom], axiom, rule, iterations, step, angle, seed) → L-系统像素植物新项目 · `sim_particles`(session_id, preset[fire/rain/starfield/explosion/smoke], seconds, seed, width, height, rate, soft) → 确定性粒子模拟最后一帧新项目

### v4-color（7）

`color_simulate`(session_id, deficiency[protan/deutan/tritan/achromat], severity, model[machado/brettel]) → 色觉缺陷模拟（原位） · `color_contrast_audit`(session_id, level[aa/aaa]) → 调色板 WCAG 全对比矩阵（最差优先） · `color_contrast_suggest`(color, against, level, large) → 保色相可达性替换色建议 · `color_name`(color 或 session_id) → CSS 精确名 + 最近名（ΔE） · `color_temperature`(kelvin 或 session_id, strength) → 开尔文色温应用/单色换算 · `color_white_balance`(session_id, strength) → 灰世界自动白平衡（原位） · `color_comparison_strip`(session_id, severity, cell_width, cell_height) → 原图+四模拟并排条新项目

### v4-vector（3）

`frame_contours`(session_id, color, tolerance, simplify) → 走廊格轮廓环（洞标记 + path 字符串） · `export_svg`(session_id, mode[runs/outline], title) → 静态 SVG（每色一 path，b64 返回） · `export_svg_animated`(session_id, frame_duration_ms, loop, title) → SMIL 动画 SVG（每帧一个 g + 离散 opacity 驱动）

**累计：158 个 MCP 工具**（v1 58 + v2 36 + v3 31 + v4 33）。

## V5（11 个）— Agent 视觉（第 5 轮：让 Agent 看见画布）

修复"盲画"问题：此前 158 个工具全是写操作，Agent 画完无法核对。V5 全部只读 —— 不产生撤销记录、不改动会话。

### v5-read（4）

`canvas_read`(session_id, x, y, width, height, format[hex/palette_index/rle/sketch], palette_id, frame_index) → 区域读回：hex 色格 / 调色板索引格 / 行程编码 / 草图（图例+字符格，可改后经 sketch_draw 画回） · `canvas_ascii`(session_id, style[letters/shades/blocks], max_width 4..256, ascii_only, invert_shades, palette_id) → ASCII 艺术渲染 + 图例（letters 按频次 A,B,C… 一色一字；shades 亮度梯 " .:-=+*#%@"；blocks 半块压缩两行一行） · `pixel_probe`(session_id, x, y, radius 1..4) → 单像素 + 3x3(可扩) 邻域格 + CSS 命名 · `canvas_legend`(session_id, palette_id) → 整幅画布草图化（图例行 `C=#hex` + 字符格）供编辑回画

### v5-describe（4）

`canvas_describe`(session_id, sections[colors,structure,symmetry,fingerprint], max_colors, max_blobs, palette_id) → 自然语言描述（尺寸/占用/主色/区域结构/对称/布局指纹 + 调色板覆盖审计） · `canvas_stats`(session_id) → 数字摘要（占用比/可见/透明/半透明/独立色数/内容框/孤点数） · `canvas_colors`(session_id, max_colors, palette_id) → 色彩普查（每色 count+hex+CSS 名+族系；带 palette_id 时附覆盖：已用条目/离板色） · `canvas_frames_summary`(session_id) → 逐帧动画概览（占用/主色/内容框）

### v5-interpret（3）

`canvas_structure`(session_id, connectivity[four/eight], max_blobs) → 连通区域解读（面积/外接框/主色/洞/贴边/填充比/形状判词"实心块/环形/细轮廓"）+ 宏观占用指纹格 · `canvas_symmetry`(session_id, tolerance 0..255) → 水平/垂直/180°/对角对称探针（逐轴 holds + 失配对数） · `canvas_diff`(session_id, mode[last_op/frames/sessions], frame_a, frame_b, other_session_id) → 差异报告（+增/-删/~改色像素数、各类包围框、色变迁移表、一句总结；last_op 对比最近一次操作前快照，引擎 peekBefore 非变异读取）

**累计：169 个 MCP 工具**（v1 58 + v2 36 + v3 31 + v4 33 + v5 11）。

### 本轮契约修复（Agent 可理解性）

`canvas_create` / `project_new` 新增可选 `session_id` 参数：LLM 天然会传会话 id 固定新画布；此前该参数被静默忽略、画布落在服务端随机 id 上，后续 draw 又按 auto-create 语义另建 16x16 默认会话，导致尺寸参数看似失效。现在：指定 id 直接命名会话；id 已占用时报 `-32602`（提示换 id 或 canvas_info 查看）。

## V6（14 个）— Agent 工效学（第 6 轮：批量/检查点/持久化/校验 + 闭环补齐）

解决长会话四大痛点：**网络延迟**（一图元一往返）、**易碎性**（中途参数错留下半成品）、**不持久**（进程死即丢会话）、**不可验证**（改没改要全量读画布）；本轮补上**视觉闭环**（sketch_draw）与磁盘项目删除。

### v6-batch（1）

`draw_batch`(session_id, ops[64 上限], dry_run) → **一次调用多个图元**。ops 为对象数组，每项 `{op: 'pixel'|'line'|'rect'|'circle'|'stroke'|'fill'|'replace'|'erase', ...参数}`（参数与同名 draw_* 工具一致）。**所有 batch op 一律落在 ACTIVE frame 上，没有 frame_index 参数——先 frame_set_active 选帧再批量**。**验证先行**：所有 op 先于任何引擎调用校验（几何/笔宽/填充种子/图层锁），坏 op 报 `op[i] (kind): 原因`，绝无半执行批次。返回逐 op applied/noop、聚合变更摘要（+增/-删/~改像素 + 各类包围框）、前后指纹、新增历史条数。`dry_run=true` 只校验不落笔。每个 applied op 一条撤销记录（label `batch:<kind>`），配合 checkpoint 整批回滚。

### v6-verify（1）

`canvas_checksum`(session_id, frame_index?, scope=frame|project) → FNV-1a 64 十六进制指纹 + 可见像素数。alpha 零色 RGB 通道规范化（透明即等价），尺寸混入摘要防前缀碰撞。改一字节即变——比 canvas_read 便宜得多的"落笔了吗"。`scope=project` 逐帧给出 `{index, checksum, visible_pixels}`，另附 `project_checksum`（逐帧摘要按帧序拼接后串的 ASCII 字节再做一次 FNV-1a 64——算法在描述里写明，跨进程稳定）。

### v6-checkpoint（4）

`checkpoint_set`(session_id, name) → 命名撤销深度锚（32 个/会话 FIFO，同名覆盖）· `checkpoint_list`(session_id) → 检查点清单（深度/时间/上层 label）· `checkpoint_rollback`(session_id, name) → **一次调用回滚到锚点**（幂等；项目血脉切换或检查点不存在报 `-32602`，不再是 isError）· `checkpoint_delete`(session_id, name)。典型流：set → 险改 → canvas_diff 核对 → 不满意即 rollback。

### v6-persist（5）

`session_save`(session_id, name) → 存档为命名槽（字母/数字/._- 64 上限；SlotStore 槽文档 + ProjectStore 像素，原子写）· `session_load`(name, session_id?) → 载入槽（无 session_id 新建会话）· `session_saved_list` → 存档清单（最新优先 + 指针完好性 + 项目在盘性）· `session_saved_delete`(name) → 只删槽指针，像素保留 · `project_delete`(project_id) → 从持久化根删除项目像素本体（槽指针变为不可读；内存会话不受影响）。需服务端以 `persistenceRoot` 启动（`PixelMcpServer(config, persistenceRoot)`）；未配置时这五个工具报可操作配置错误。**服务器重启/换对话续作**：save → 重启 → load。

### v6-hygiene（2）

`session_rename`(session_id, name) → 项目改名（不产生撤销记录）· `project_export_json`(session_id) → 导出 v2 线格式文档（data_b64 + byte_count，≤ 2MB；超出时提示 session_save）——补上 io_import_project 的反向通道，跨服务器迁移/嵌入提示词。

### v6-sketch（1）

`sketch_draw`(session_id, sketch, x?, y?, frame_index?) → 把 canvas_read(format="sketch") / canvas_legend 的文本**画回会话**：图例行 `C=#RRGGBB|#AARRGGBB`、`---` 分隔、字符格（'.' 透明）。解析后的非透明格盖印到指定帧（默认 active）的活动 cel；x/y ≥ 0，越界格裁剪并以 placed/clipped 计数返回，另附 parsed {width,height,points} 与 projectSummary。读 → 改文本 → 画回，视觉闭环落地。

**累计：183 个 MCP 工具**（v1 58 + v2 36 + v3 31 + v4 33 + v5 11 + v6 14）。
