# Pixel Lab

**像素能力库（pixel capability library）** — 可被宿主 Agent 应用引入的像素艺术引擎。Kotlin Multiplatform-style API + 可选 C++/NDK 热路径 + MCP 服务器封装。

> 为 [Android-Guru-Agent](https://github.com/AceGuru-mjh/Android-Guru-Agent) 而生，但零宿主耦合：纯 JVM 核心、不可变数据模型、无全局状态。

## 模块

| 模块 | 定位 | 关键能力 |
|:---|:---|:---|
| `pixel-core` | 主战场 | 数据模型（PixelFrame/SpriteProject）、绘制引擎+撤销栈、动画引擎、模板/字体、量化/抖动/转换管线、PNG/GIF/APNG/精灵表/Codex 宠物包导出、C++/NDK 热路径 |
| `pixel-ui-compose` | Compose 原子组件 | PixelCanvas（缩放/平移/笔画预览/洋葱皮/网格）、PalettePanel、TimelineStrip、LayerPanel |
| `pixel-mcp` | MCP 服务器 | JSON-RPC 2.0 over SSE、55 工具、会话存储、零外部依赖（手写 HTTP/JSON） |
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

- 全模块 kotlinc 2.0.21 编译 0 error
- C++ 全文件 g++ `-Wall -Wextra` 语法 0 警告级问题 + 行为冒烟全绿（量化 3 算法确定性/抖动 7 核/洪泛/合成/LZW 回环/GIF 结构）
- GIF/PNG/APNG 编码经 JDK ImageIO / 独立解码器像素级验证
- MCP 服务器真实端口冒烟：initialize → tools/list(55) → tools/call → SSE 事件流

## License

MIT
