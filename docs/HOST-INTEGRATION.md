# Host Integration Guide — Android-Guru-Agent 接入

Pixel Lab 是能力库，宿主是 [Android-Guru-Agent](https://github.com/AceGuru-mjh/Android-Guru-Agent)（Kotlin 2.0.21 / AGP 8.7.3 / 13 Gradle 模块 / Hilt DI / 单 Activity Compose）。本指南给出三条接入路径与硬约束。

## 版本对齐（硬约束）

| 项 | 宿主 | Pixel Lab | 兼容性 |
|:---|:---|:---|:---|
| Kotlin | 2.0.21 | 2.0.21 | ✅ 完全一致 |
| compileSdk | 35 | 35 | ✅ |
| minSdk | 26 | 26 | ✅ |
| JVM target | 17 | 17 | ✅ |
| Compose BOM | 2024.12.01 | 2024.12.01（compileOnly 语义，宿主侧提供） | ✅ |
| NDK/CMake | 27 / 3.22.1 / C++17 / c++_shared | 相同 | ✅ |
| ABI | arm64-v8a, armeabi-v7a, x86_64 | arm64-v8a, x86_64（库侧子集，宿主 abiFilters 取并集即可） | ✅ |
| 混淆 | 不启用 | consumer-rules 保留 JNI 类 | ✅ |

宿主曾因 Kotlin 元数据版本踩坑（Lucide 2.x、haze 选型记录）：Pixel Lab 与宿主 Kotlin 完全同版本，无元数据风险。

## 路径 A：进程内 Kotlin API（推荐起步）

```kotlin
// 宿主 app/di/PixelModule.kt
@Provides @Singleton
fun providePixelLab(): PixelLab = PixelLab.create(
    PixelLabConfig.Builder()
        .maxUndoDepth(200)
        .logger(hostLogger)   // 接入宿主日志
        .build()
)
```

使用点直接调 `lab.engine / lab.animation / lab.template / lab.converter / lab.exporter`。注意：`PixelEngine` 撤销栈是实例态，**每 project 串行访问**（单协程或互斥）。

## 路径 B：MCP 服务器（网络隔离）

```kotlin
val server = PixelMcpServer(PixelLabConfig.default())
val handle = server.start(8090)   // SSE: http://127.0.0.1:8090/sse
// 宿主用现成 mcp_connect HTTP/SSE 传输接入，55 个工具即刻可用
```

零外部依赖（java.net.ServerSocket + 手写 HTTP/SSE/JSON），不会与宿主 OkHttp/Ktor 版本冲突。工具输出纪律：图像数据只返回字节数与摘要，**绝不返回 base64 大图**（宿主 ToolOutputTruncator 头 1200 + 尾 600 字符截断）。

## 路径 C：BUILTIN 进程内 transport（性能最优）

参考宿主 `app/di/McpModule.kt` 的 `BuiltinGithubMcpTransport`（430 行 JSON-RPC 派发范例）：实现 `McpTransportHandle`，把宿主的 JSON-RPC 请求直接路由到 `PixelMcpServer` 的处理器（不走 socket）。零网络开销，工具注册进宿主 `ToolRegistry` 后由 `SafeAgentTool` 统一包装。

### AgentTool 适配器（terminal 模式，保持库独立）

```kotlin
class PixelDrawTool(private val lab: PixelLab) : AgentTool {
    override val id = "pixel_draw_pixel"
    override val description = "Draw a single pixel on the active canvas"
    override val schema = """{"x":0,"y":0,"color":"#FF0000","session":"default"}"""
    override suspend fun execute(params: String): ToolResult {
        val p = lab.parseJsonParams(params)   // 宿主自有 JSON 栈解析
        val next = lab.engine.drawPixel(session.project, p.x, p.y, p.color)
        session.project = next
        return ToolResult.success("drew (${p.x},${p.y}); canvas now ${countOpaque(next)} px")
    }
}
```

## 工具纪律（宿主硬约束）

1. **id 风格**：宿主工具 id 为点分风格由宿主自行前缀；Pixel Lab MCP 工具名是 snake_case（`draw_pixel`），宿主注册时可加 `pixel.` 前缀。
2. **输出截断**：长操作（GIF 编码）宿主侧已有 `ToolStreamEvent.Progress` 流式进度与 `ToolRunPolicy` 长超时条目，Pixel Lab 的导出工具返回 `byte_count` + 文件路径摘要即可。
3. **文件互通**：导出文件写入宿主 workspace（`filesDir/linux/workspaces/default`）后与宿主 `read_file`/`download_file` 工具链互通。

## ViroPet 协同

宿主 ViroPet 消费统一网格 sprite sheet（9×8 格 208×264）。Pixel Lab 的 `exportSpritesheet(GRID)` 与 `exportCodexPet` 输出同构网格 + JSON 元数据（帧区间/轨道），可直接对接该消费模式：

```kotlin
val zip: ByteArray = lab.exporter.exportCodexPet(
    petName = "mimi",
    project = project,
    trackMap = mapOf("idle" to 0..5, "walk" to 6..11, "sleep" to 12..17),
).getOrThrow()   // spritesheet.png (1536×1872) + pet.json (9 轨道)
```

## 发布

开发期 JitPack（`com.github.AceGuru-mjh:Pixel-Lab:<tag>`）；稳定后 Maven Central（vanniktech 插件已预留目录结构）。
