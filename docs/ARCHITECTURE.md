# Architecture

```
pixel-lab/
├── pixel-core/                      # 纯 JVM 核心（唯一 Android 触点：BitmapIo/Exporter 的 Bitmap 出口）
│   └── src/main/kotlin/com/pixellab/core/
│       ├── model/                   # PixelFrame · SpriteProject · Frame · Layer · Palette · CodexPetSpec（冻结层）
│       ├── palette/                 # BuiltInPalettes（6 套）· LabColor（CIELAB）
│       ├── engine/                  # PixelEngine（28 API + 撤销栈）· DrawOps（几何）· FloodFill（扫描线）
│       ├── animation/               # AnimationEngine（帧/FPS/标签/洋葱皮/呼吸）
│       ├── template/                # TemplateEngine · FontData（5×7 + 8×8 全 ASCII）· TemplateLibrary（6 模板）
│       ├── convert/                 # ImageConverter 管线 · Quantizer/Ditherer 门面 · Kotlin 回退 ×2
│       ├── export/                  # PngCodec · KotlinGifEncoder · ApngEncoder · BitmapIo · Exporter · Spritesheet · CodexPet
│       ├── nativelib/               # JNI 绑定（NativeLib/Quantizer/Ditherer/PixelOps/GifEncoder）
│       └── PixelLab / PixelLabConfig / PixelResult
│   └── src/main/cpp/                # C++17 热路径
│       ├── common/                  # color_utils.h（Lab 变换/距离）· jni_common.h（RAII pin）
│       ├── quantization/            # median_cut / kmeans / octree
│       ├── dithering/               # 7 核抖动
│       ├── gif/                     # gif_encoder + lzw
│       ├── pixel_ops/               # 批量写/洪泛/合成
│       └── jni/jni_core.cpp         # 全部 JNI 入口（小而精接口）
├── pixel-ui-compose/                # Compose 原子组件（Material3、状态提升、无业务耦合）
├── pixel-mcp/                       # MCP 服务器（零依赖：ServerSocket HTTP/SSE + 手写 JSON）
└── sample-app/                      # 演示 App
```

## 数据流

```
Bitmap ──BitmapIo──▶ ImageData ──ImageConverter──▶ PixelFrame + Palette
                                                    （重采样→量化/映射→抖动→边缘清理）
PixelFrame ◀──with* 复制语义──▶ SpriteProject（帧×图层 cel 矩阵）
SpriteProject ──PixelEngine──▶ 绘制/变换（撤销栈按 project.id）
             ──AnimationEngine──▶ 帧管理/标签/呼吸
             ──compositeFrame──▶ 合成帧 ──Exporter──▶ PNG/APNG/GIF/精灵表/Codex ZIP
```

## 关键决策

1. **cel 模型**：`Frame.cels[layerId]` 稀疏存储——空图层不占内存，多帧共享不可变 PixelFrame 实例（copy-on-write）。
2. **合成即时计算**：`compositeFrame()` 每次合成（图层少、画布小，快于维护合成缓存的一致性成本）。
3. **撤销历史只在 PixelEngine**：单一历史所有者，异常路径零污染（先校验后计算再入栈）。
4. **Kotlin 回退与 C++ 语义逐条对齐**（透明判定 0x80、误差扩散扫描序、Bayer 阈值、容差谓词），唯一已知分歧：KMEANS 的 RNG（java.util.Random vs mt19937 同种子值）——两路径各自确定。
5. **MCP 零依赖**：Android 上无 com.sun.net.httpserver；ServerSocket + 手写 HTTP/1.1 + SSE 全平台可用，且与宿主网络栈零版本冲突。
6. **字体双轨**：Font5x7（列编码经典工业字体）+ Font8x8（行编码手绘粗体，init 转列），PixelFont 接口统一。

## 性能预算

- 32×32 画布全帧合成：< 0.1 ms（JVM）
- 256 色 MedianCut 量化 512×512：< 50 ms（C++）/ < 300 ms（Kotlin 回退）
- GIF 8 帧 64×64：< 5 ms（C++ LZW）
- 撤销栈深 100（默认）：IntDeque 淘汰 O(1)
