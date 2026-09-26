# MCP Tools — 151 个工具参考（tier 1–4 全量）

传输：JSON-RPC 2.0，`GET /sse`（事件流）+ `POST /messages`（请求/响应并镜像至 SSE）；可选 RFC 6455 WebSocket 通道（`start(port, websocketPort)`）。请求体支持 Content-Length 与 chunked。
会话：`session_id` 参数（缺省自动创建，LRU 32 上限；生成类工具无 `session_id` 时自动新建）。
错误形状：参数错误 `-32602` / 工具崩溃 `isError` / 未知工具 `-32601`。
图像纪律：所有导出工具返回 `byte_count` 与摘要，不返回 base64。
派发：`McpToolRouter` 层级链（tier 1 canvas/draw/layer/frame/palette/anim/text/template/export/project 55 个 → tier 2 形状/画笔/选区/对称/样式 35 个 → tier 3 io/生成/图集/tilemap/管线 29 个 → tier 4 分析/变换/色彩/矢量 32 个），`tools/list` 每条带 `tier` 字段。

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

## layer_*（8）

`layer_add`(name?) · `layer_remove`(layer_id) · `layer_rename`(layer_id,name) · `layer_move`(layer_id,to_index) · `layer_set_opacity`(layer_id,opacity) · `layer_set_visible`(layer_id,visible) · `layer_set_locked`(layer_id,locked) · `layer_list`()

## frame_*（6）

`frame_add`(after_index?) · `frame_clone`(frame_index) · `frame_delete`(frame_index) · `frame_move`(from,to) · `frame_set_duration`(frame_index,duration_ms|null) · `frame_list`()

## palette_*（3）

`palette_list`（6 内置：pico-8/gameboy/nes54/endesga-32/clawd/kimi）· `palette_switch`(palette_id) · `palette_closest_color`(color→Lab 最近邻索引+hex)

## convert_*（3）

`convert_image`(pixels[]|说明, width, height, target_w?, target_h?, color_count?, dither?) · `convert_analyze`(同输入→建议尺寸/色数/调色板) · `convert_refine`(snap_alpha?, remove_stray?)

## anim_*（4）

`anim_set_fps`(fps) · `anim_tag`(name,start,end) · `anim_breathe`(amplitude?) · `anim_preview_count`()

## text_* / template_*（3）

`text_generate`(text, color, font?="5x7", scale?) → 尺寸+非零像素数 · `template_list`（6 模板）· `template_apply`(template_id) → 帧数/图层数

## export_*（5）

`export_png`(scale?) · `export_spritesheet`(layout?, columns?, scale?, margin?) · `export_gif`(loop_count?, dither?) · `export_apng`(loop_count?) · `export_codex_pet`(pet_name?, tracks?) — 全部返回 `byte_count`

## project_*（5）

`project_new`(width,height,palette_id?) · `project_state` · `project_list`（会话目录）· `project_undo` · `project_redo`

## JSON-RPC 示例

```json
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}
{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"draw_pixel","arguments":{"x":3,"y":4,"color":"#ff8800"}}}
```

响应：`{"jsonrpc":"2.0","id":2,"result":{"content":[{"type":"text","text":"{\"ok\":true,...}"}]}}`

## V3 — 导入/生成/瓦片地图/图集/历史/流水线（29 个新工具，v3 传输含 WebSocket）

V3 注册表与 V1/V2 并列挂载（`McpToolRegistryV3(lab)`），新增 `transport/WebSocketServer.kt`：手写 RFC 6455 WebSocket（SHA-1 握手、帧编解码、分片、ping/pong、掩码强制校验），与 SSE 传输共用 JSON-RPC 核心。

### v3-io（6）

| 工具 | 参数 | 说明 |
|:---|:---|:---|
| `io_import_frames` | data_b64 | 魔数嗅探 PNG/APNG/GIF/QOI/BMP/ASE → 格式/尺寸/帧数/延迟/调色板提示 |
| `io_import_project` | data_b64, name? | 项目 JSON（v2 线格式）→ 会话项目 |
| `io_export_qoi` | frame_index?, session_id? | 合成帧 → QOI 1.0 字节 |
| `io_export_bmp` | frame_index?, session_id? | 合成帧 → 32 位 BMP |
| `io_export_ico` | sizes?, session_id? | 合成帧 → 多分辨率 ICO（默认 [16,32,48]） |
| `io_export_atlas` | padding?, extrude?, trim?, format?, scale? | 全帧打包图集 PNG + 引擎元数据（generic/godot3/godot4/tiled/phaser） |

### v3-gen（10）

`gen_texture`(type: clouds/wood/stone/marble/bricks/checker, width, height, palette_id?|colors[], seed?, rings?/veins?/brick_w?/brick_h?/mortar?/cell?) · `gen_sprite`(type: tree/rock/gem/ship, size?|width/height, colors?, seed?) · `gen_noise_field`(width, height, seed, tile?, octaves?) · `gen_silhouette`(color) · `outline`(color, mode: outer/inner, connectivity: 4/8) · `shade`(light_angle?, strength?, dark_color?, light_color?) · `ramp`(base_color, steps, lighten?, darken?) → 纯函数 hex 列表 · `flip_rotate`(op: fliph/flipv/rot90cw/rot90ccw/rot180) · `canvas_resize`(width, height, anchor: center/corner) · `project_thumbnail`(frame_index?, max_size?≤64)

### v3-map（5）

`tilemap_create`(rows[]: "01" 字符串, tile_size?, tileset?) → map_id · `tilemap_autotile`(map_id, terrain_tag, mode: blob47/simple16/wang) · `tilemap_render`(map_id, scale?) → PNG · `tilemap_iso_render`(map_id, tile_w?, tile_h?, height_offsets[]?) → PNG · `iso_diamond_mask`(tile_w, tile_h) → PNG

### v3-atlas（2）

`texture_trim`(minimum?) → 偏移量 · `texture_extrude`(amount) → 抗缝隙外扩

### v3-history（4）

`history_status` → 深度/条目标签/脏标记 · `history_undo` · `history_redo` · `history_mark_saved` — 每会话独立命令历史，创建/编辑/生成全部可撤销

### v3-pipeline（2）

`pipeline_run`(recipe_json) → 步骤顺序执行（scale/quantize/dither/palette-map/outline/trim/posterize/bg-remove） · `pipeline_recipe_validate`(recipe_json) → 步骤摘要（纯校验）

## V4（32 个）— 第 4 轮能力（分析 / 变换 / 世界生成 / 色彩科学 / 矢量）

### v4-analysis（10）

`frame_histogram`(session_id) → R/G/B/A/Rec.709 luma 通道统计（均值/方差/熵/分位数/透明比） · `frame_otsu`(session_id, levels, binarize) → Otsu 单级/多级（DP）阈值，可选二值化 · `frame_equalize`(session_id) → 保色相 luma 直方图均衡 · `frame_autolevels`(session_id, low_pct, high_pct) → 百分位对比度拉伸 · `frame_convolve`(session_id, kernel[13 种], edge[clamp/wrap/transparent], alpha[premultiplied/straight/alpha_only]) → 卷积 · `frame_morphology`(session_id, op[8 种], element[square3/cross3/square5]) → 膨胀/腐蚀/开闭/去孤点/填洞/描边/清理 · `frame_components`(session_id, connectivity, mode) → 连通域清单（面积/包围盒/周长/洞/边界） · `frame_metrics`(session_id, frame_index_b, frame_index_a, tolerance) → MAE/PSNR/差异比/差异框 · `frame_audit`(session_id, tiny_cluster_area, similar_tolerance) → 像素工艺审计（尘点/断角/棋盘/洞/微簇/锯齿）+ 0-100 评分 + 建议 · `frame_remove_small`(session_id, min_area, connectivity) → 按面积去斑

### v4-transform（6）

`frame_rotate`(session_id, degrees, bounds[expand/crop]) → RotSprite 三次剪切任意角度（crop 原位 / expand 新项目） · `frame_scale2x`(session_id, factor[2/3], variant[plain/corners]) → AdvMAME Scale2x/3x 新项目 · `frame_epx`(session_id, factor[2/3]) → EPX 整数放大新项目 · `frame_xbr`(session_id) → xBR2x 新项目 · `frame_resample`(session_id, width, height, mode[nearest/box]) → 半像素中心重采样新项目 · `frame_mipmap`(session_id, max_levels) → 只读金字塔（尺寸 + 每级 PNG b64）

### v4-worldgen（6）

`gen_worley`(session_id, width, height, seed, feature[f1/f2/border/cell], cell_size, contrast) → Worley 噪声新项目 · `gen_dungeon`(session_id, width, height, seed, min_leaf) → BSP 地牢（房间/走廊/门）新项目 · `gen_cave`(session_id, width, height, seed, fill_chance, smooth_passes, walk_tiles) → 元胞自动机洞穴新项目 · `gen_biome`(session_id, width, height, seed, theme[overworld/volcanic/frozen], height_scale, moisture_scale) → 生物群系世界图新项目 · `gen_lsystem`(session_id, preset[bush/tree/koch/fern/custom], axiom, rule, iterations, step, angle, seed) → L-系统像素植物新项目 · `sim_particles`(session_id, preset[fire/rain/starfield/explosion/smoke], seconds, seed, width, height, rate, soft) → 确定性粒子模拟最后一帧新项目

### v4-color（7）

`color_simulate`(session_id, deficiency[protan/deutan/tritan/achromat], severity, model[machado/brettel]) → 色觉缺陷模拟（原位） · `color_contrast_audit`(session_id, level[aa/aaa]) → 调色板 WCAG 全对比矩阵（最差优先） · `color_contrast_suggest`(color, against, level, large) → 保色相可达性替换色建议 · `color_name`(color 或 session_id) → CSS 精确名 + 最近名（ΔE） · `color_temperature`(kelvin 或 session_id, strength) → 开尔文色温应用/单色换算 · `color_white_balance`(session_id, strength) → 灰世界自动白平衡（原位） · `color_comparison_strip`(session_id, severity, cell_width, cell_height) → 原图+四模拟并排条新项目

### v4-vector（3）

`frame_contours`(session_id, color, tolerance, simplify) → 走廊格轮廓环（洞标记 + path 字符串） · `export_svg`(session_id, mode[runs/outline], title) → 静态 SVG（每色一 path，b64 返回） · `export_svg_animated`(session_id, frame_duration_ms, loop, title) → SMIL 动画 SVG（每帧一个 g + 离散 opacity 驱动）

**累计：151 个 MCP 工具**（v1 34 + v2 26 + v3 59 + v4 32）。

## V5（11 个）— Agent 视觉（第 5 轮：让 Agent 看见画布）

修复"盲画"问题：此前 151 个工具全是写操作，Agent 画完无法核对。V5 全部只读 —— 不产生撤销记录、不改动会话。

### v5-read（4）

`canvas_read`(session_id, x, y, width, height, format[hex/palette_index/rle/sketch], palette_id, frame_index) → 区域读回：hex 色格 / 调色板索引格 / 行程编码 / 草图（图例+字符格，可改后经 sketch_draw 画回） · `canvas_ascii`(session_id, style[letters/shades/blocks], max_width 4..256, ascii_only, invert_shades, palette_id) → ASCII 艺术渲染 + 图例（letters 按频次 A,B,C… 一色一字；shades 亮度梯 " .:-=+*#%@"；blocks 半块压缩两行一行） · `pixel_probe`(session_id, x, y, radius 1..4) → 单像素 + 3x3(可扩) 邻域格 + CSS 命名 · `canvas_legend`(session_id, palette_id) → 整幅画布草图化（图例行 `C=#hex` + 字符格）供编辑回画

### v5-describe（4）

`canvas_describe`(session_id, sections[colors,structure,symmetry,fingerprint], max_colors, max_blobs, palette_id) → 自然语言描述（尺寸/占用/主色/区域结构/对称/布局指纹 + 调色板覆盖审计） · `canvas_stats`(session_id) → 数字摘要（占用比/可见/透明/半透明/独立色数/内容框/孤点数） · `canvas_colors`(session_id, max_colors, palette_id) → 色彩普查（每色 count+hex+CSS 名+族系；带 palette_id 时附覆盖：已用条目/离板色） · `canvas_frames_summary`(session_id) → 逐帧动画概览（占用/主色/内容框）

### v5-interpret（3）

`canvas_structure`(session_id, connectivity[four/eight], max_blobs) → 连通区域解读（面积/外接框/主色/洞/贴边/填充比/形状判词"实心块/环形/细轮廓"）+ 宏观占用指纹格 · `canvas_symmetry`(session_id, tolerance 0..255) → 水平/垂直/180°/对角对称探针（逐轴 holds + 失配对数） · `canvas_diff`(session_id, mode[last_op/frames/sessions], frame_a, frame_b, other_session_id) → 差异报告（+增/-删/~改色像素数、各类包围框、色变迁移表、一句总结；last_op 对比最近一次操作前快照，引擎 peekBefore 非变异读取）

**累计：162 个 MCP 工具**（v1 34 + v2 26 + v3 59 + v4 32 + v5 11）。

### 本轮契约修复（Agent 可理解性）

`canvas_create` / `project_new` 新增可选 `session_id` 参数：LLM 天然会传会话 id 固定新画布；此前该参数被静默忽略、画布落在服务端随机 id 上，后续 draw 又按 auto-create 语义另建 16x16 默认会话，导致尺寸参数看似失效。现在：指定 id 直接命名会话；id 已占用时报 `-32602`（提示换 id 或 canvas_info 查看）。
