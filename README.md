# Pixel Lab

**像素能力库（pixel capability library）** — 可被宿主 Agent 应用引入的像素艺术引擎。Kotlin Multiplatform-style API + 可选 C++/NDK 热路径 + MCP 服务器封装。

> 为 [Android-Guru-Agent](https://github.com/AceGuru-mjh/Android-Guru-Agent) 而生，但零宿主耦合：纯 JVM 核心、不可变数据模型、无全局状态。

## 模块

| 模块 | 定位 | 关键能力 |
|:---|:---|:---|
| `pixel-core` | 主战场 | 数据模型（PixelFrame/SpriteProject）、绘制引擎+撤销栈、动画引擎、模板/字体、量化/抖动/转换管线、PNG/GIF/APNG/精灵表/Codex 宠物包导出、C++/NDK 热路径 |
| `pixel-ui-compose` | Compose 原子组件 | PixelCanvas（缩放/平移/笔画预览/洋葱皮/网格）、PalettePanel、TimelineStrip、LayerPanel |
| `pixel-mcp` | MCP 服务器 | JSON-RPC 2.0 over SSE + 可选 WebSocket、四层注册表 151 工具（tier-chain 路由）、会话存储、零外部依赖（手写 HTTP/SSE/WS/JSON） |
| `sample-app` | 演示 | 单 Activity 全功能画板 + Agent 指令行 + 四格式导出 |

## 快速开始

```kotlin
val lab = PixelLab.create()
val project = SpriteFactory.create("hero", 32, 32, BuiltInPalettes.PICO8)

// 绘制（一次调用 = 一个撤销单元）
val drawn = lab.engine.drawPixels(project, listOf(PixelPoint(1, 1)), 0xFF000000)
val circle = lab.engine.drawCircle(drawn, 16, 16, 6, 0xFFFFA300.toInt(), filled = true)

// 动画
val animated = lab.animation.breathe(circle, amplitudePx = 1)

// 导出
val gif: ByteArray = lab.exporter.exportGif(animated).getOrThrow()
```

宿主接入（不依赖 Compose）只引 `pixel-core`；带 UI 引 `pixel-ui-compose`；Agent 工具化引 `pixel-mcp`。详见 [docs/HOST-INTEGRATION.md](docs/HOST-INTEGRATION.md)。

## 架构原则

1. **不可变数据**：`PixelFrame`/`SpriteProject` 全部 `with*` 复制语义，内容级 equals，线程安全共享。
2. **无全局状态**：引擎实例化持配置；撤销栈按 project.id 键控在 `PixelEngine` 内。
3. **结果对象**：边界 API（转换/导出/MCP）返回 `PixelResult`，程序员错误抛 IAE。
4. **确定性**：相同输入 → 相同输出字节（固定随机种子、稳定排序、钉死 ZIP 时间戳）。
5. **原生可选**：`NativeLib.load()` 失败自动回退纯 Kotlin，语义一致。
6. **零依赖哲学**：PNG/GIF/LZW/CRC/HTTP/SSE/JSON 全部手写实现；唯一运行依赖 kotlinx-coroutines。

## 原生热路径（C++/NDK）

| 路径 | 算法 |
|:---|:---|
| 颜色量化 | MedianCut / KMeans(CIELAB, 固定种子) / Octree |
| 抖动 | Floyd-Steinberg / Atkinson / Bayer 2×2·4×4·8×8 / Checkerboard |
| GIF 编码 | GIF89a + LZW(9..12 变码长) + 帧内量化+抖动 |
| 批量像素 | setPixelsBatch / 扫描线洪泛（含容差）/ 图层合成 |

ABI：`arm64-v8a` + `x86_64`；C++17；`-Wall -Wextra` 干净。桌面端已通过 g++ + ASan/UBSan 行为冒烟 + JDK ImageIO 像素级交叉验证。

## 技术栈

Kotlin 2.0.21 · AGP 8.7.3 · compileSdk 35 · minSdk 26 · JDK 17 · Compose BOM 2024.12.01 · NDK 27 / CMake 3.22.1 · kotlinx-coroutines 1.9.0

## 验证状态

- 全模块 kotlinc 2.0.21 编译 0 error（CI 含 pixel-mcp JVM 门禁）
- C++ 全文件 g++ `-Wall -Wextra` 语法干净 + ASan/UBSan 行为冒烟全绿（量化 3 算法确定性/抖动 7 核/洪泛/合成/LZW 回环/GIF 结构）
- GIF/PNG/APNG 编码经 JDK ImageIO / 独立解码器像素级验证
- MCP 服务器真实端口冒烟：initialize → tools/list → tools/call → SSE 事件流 + **WebSocket（RFC 6455 握手/掩码/分片/ping/close 1002 活体测试）**
- **集成验证（本轮）**：26/26 协议探针（含 chunked POST / 100-Continue 大请求体 / SSE 镜像 / WS 双向 / 全四层工具可达）+ 151 工具全量冒烟（0 崩溃 0 未知工具）+ **Agent 理解力实测**（真实 LLM 仅凭 tools/list 对 8 条中文用户需求的工具选择与参数 8/8 正确执行）
- 十二个增强 PR 逐一通过行为冒烟后合并（PR7-11 冒烟合计 565 断言全绿）
- 冒烟测试修复过的真实缺陷：JDK Inflater 零容量自旋、Aseprite 现行规范 3 处布局偏差、PixelFrame.rotated90Ccw 索引笔误
- **本轮修复**（集成验证驱动）：①96/151 工具从未接线（tier-chain 路由器补齐）②WebSocket 传输从未启动（接入 `start(port, wsPort)`）③`100-Continue` 用 `use{}` 关闭 socket（大请求体必挂）④`width*height` Int 溢出可致 native 堆越界（Long 域校验 + 8192 边长门禁）⑤画线无界坐标 DoS（跨度上限）⑥v4 参数错误错误形状（补 -32602 映射）⑦撤销历史随会话驱逐泄漏（双回调清理）⑧native 量化丢 alpha（镜像 Kotlin 契约）⑨HTTP 不支持 chunked⑩`\u+041` 非法转义被接受

## 规模与演进

| 阶段 | 内容 | 规模 |
|:---|:---|:---|
| 基础版 | 4 模块 + C++ 热路径 + 文档/CI | 11,206 行 Kotlin + 2,120 行 C++ |
| PR #1 | 高级工具引擎（几何/选区/混合/对称/笔刷） | +1,812 行 |
| PR #2 | **优质画板** PixelCanvasPro（9 工具/蚂蚁线/HSV 取色器） | +2,269 行 |
| PR #3 | 调色板宇宙（21 板 + 互导格式 + 色彩科学） | +1,133 行 |
| PR #4 | 动画特效库（13 特效 + 7 缓动） | +1,333 行 |
| PR #5 | 文字排版引擎 v2（发光/描边/阴影 + 8 模板） | +1,525 行 |
| PR #6 | 项目序列化 + Aseprite 导出 + MCP 工具 v2（总 90 工具） | +2,270 行 |
| PR #32 | **导入管线**：GIF/PNG(APNG)/QOI/BMP 解码 + Aseprite .ase 导入 + ICO 导出 | +3,526 行 |
| PR #33 | 命令历史 + 程序化生成（纹理/精灵）+ JSON 流水线配方 | +2,999 行 |
| PR #34 | 瓦片地图（blob-47/Wang 自动贴图 + 等距渲染）+ 图集打包（MaxRects + 五格式元数据） | +2,671 行 |
| PR #35 | Compose 编辑器套件（脚手架/时间轴/取色器/色板/快捷键/CRT 预览/主题） | +3,308 行 |
| PR #36 | MCP v3：WebSocket 传输 + 29 新工具（总 119）+ ProjectStore + 画廊/编辑器 + Agent 手册 | +3,266 行 |
| PR #42 | **CI 真实化**：修复全红 CI（触发分支/Gradle 8.1/NDK 头/Compose 插件）+ quality-gate 双工作流 + 图像分析层（直方图/Otsu/卷积/形态学/连通域/PSNR/像素工艺审计）+ 首个 JUnit 套件 | +4,580 行 |
| PR #43 | 变换算法：RotSprite 三剪切旋转 + Scale2x/3x + EPX + xBR + 半像素重采样 | +1,282 行 |
| PR #44 | 世界生成：Worley/BSP 地牢/元胞洞穴/生物群系 + L-系统 + 确定性粒子系统 | +1,772 行 |
| PR #45 | 色彩科学 + 矢量：Machado/Viénot 色盲模拟 + WCAG 对比度审计 + CSS 命名 + 开尔文色温 + MarchingSquares + SVG/SMIL 导出 | +1,833 行 |
| PR #46 | MCP v4：32 新工具（总 **151**）+ 配方 13-18 | +1,249 行 |
| PR #47 | **集成验证与加固**：tier-chain 路由（151 工具全上线）+ WS 传输接线 + 10 项缺陷修复（含 100-Continue/Int 溢出/无界画线/历史泄漏）+ Agent 理解力实测 8/8 + CI 加 pixel-mcp 门禁 | +~600 行净增（含修复） |
| **合计** | | **49,680+ 行**（Kotlin 47,538+ + C++ 2,142+），**151 个 MCP 工具** |

任务看板：https://github.com/users/AceGuru-mjh/projects/5

## License

MIT
