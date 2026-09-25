# MCP Tools — 55 个工具参考

传输：JSON-RPC 2.0，`GET /sse`（事件流）+ `POST /messages`（请求/响应并镜像至 SSE）。
会话：`session_id` 参数（缺省自动创建，LRU 32 上限）。
图像纪律：所有导出工具返回 `byte_count` 与摘要，不返回 base64。

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
