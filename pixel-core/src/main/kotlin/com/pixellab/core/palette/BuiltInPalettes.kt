package com.pixellab.core.palette

import com.pixellab.core.model.Palette
import com.pixellab.core.model.PaletteSource

/**
 * The built-in palette library: classic pixel-art color sets, hand-verified
 * against their published definitions. Every palette is exposed as an immutable
 * [Palette] with source [PaletteSource.BUILTIN].
 */
object BuiltInPalettes {

    /** PICO-8 fantasy console 16 colors (Lexaloffle). */
    val PICO8: Palette = Palette(
        id = "pico-8", name = "PICO-8", source = PaletteSource.BUILTIN,
        colors = intArrayOf(
            0xFF000000.toInt(), 0xFF1D2B53.toInt(), 0xFF7E2553.toInt(), 0xFF008751.toInt(),
            0xFFAB5236.toInt(), 0xFF5F574F.toInt(), 0xFFC2C3C7.toInt(), 0xFFFFF1E8.toInt(),
            0xFFFF004D.toInt(), 0xFFFFA300.toInt(), 0xFFFFEC27.toInt(), 0xFF00E436.toInt(),
            0xFF29ADFF.toInt(), 0xFF83769C.toInt(), 0xFFFF77A8.toInt(), 0xFFFFCCAA.toInt(),
        ),
    )

    /** Original Game Boy 4-shade green ramp. */
    val GAMEBOY: Palette = Palette(
        id = "gameboy", name = "Game Boy", source = PaletteSource.BUILTIN,
        colors = intArrayOf(
            0xFF0F380F.toInt(), 0xFF306230.toInt(), 0xFF8BAC0F.toInt(), 0xFF9BBC0F.toInt(),
        ),
    )

    /** NES master palette subset: the 54 usable colors. */
    val NES54: Palette = Palette(
        id = "nes54", name = "NES 54", source = PaletteSource.BUILTIN,
        colors = intArrayOf(
            0xFF7C7C7C.toInt(), 0xFF0000FC.toInt(), 0xFF0000BC.toInt(), 0xFF4428BC.toInt(), 0xFF940084.toInt(), 0xFFA80020.toInt(), 0xFFF83800.toInt(), 0xFFE45C10.toInt(),
            0xFFAC7C00.toInt(), 0xFF503000.toInt(), 0xFF007878.toInt(), 0xFF0078F8.toInt(), 0xFF0058F8.toInt(), 0xFF6844FC.toInt(), 0xFFD800CC.toInt(), 0xFFE40058.toInt(),
            0xFFF8B800.toInt(), 0xFFACAC00.toInt(), 0xFF30AC00.toInt(), 0xFF00B800.toInt(), 0xFF00A800.toInt(), 0xFF00A844.toInt(), 0xFF008888.toInt(), 0xFF54FC54.toInt(),
            0xFF54FCB8.toInt(), 0xFF54FCFC.toInt(), 0xFF00FCFC.toInt(), 0xFF00E4D4.toInt(), 0xFF3CBC3C.toInt(), 0xFFA80054.toInt(), 0xFFA80000.toInt(), 0xFFB8F818.toInt(),
            0xFFF8B8F8.toInt(), 0xFFF8B8D8.toInt(), 0xFF787878.toInt(), 0xFFFC54FC.toInt(), 0xFFF87858.toInt(), 0xFFFCA044.toInt(), 0xFFF8B800.toInt(), 0xFFD8D8D8.toInt(),
            0xFF3C3C3C.toInt(), 0xFFB8F8B8.toInt(), 0xFFB8F8D8.toInt(), 0xFFFCFCB8.toInt(), 0xFFFCFCFC.toInt(), 0xFFF8D8F8.toInt(), 0xFFF8D878.toInt(), 0xFFFCBCB0.toInt(),
            0xFFFCFCFC.toInt(), 0xFFBCBCBC.toInt(), 0xFFFCBC3C.toInt(), 0xFF58007C.toInt(), 0xFF8C007C.toInt(), 0xFF580054.toInt(),
        ),
    )

    /** Endesga 32 — high-contrast artist palette by @ENDESGA. */
    val ENDESGA32: Palette = Palette(
        id = "endesga-32", name = "Endesga 32", source = PaletteSource.BUILTIN,
        colors = intArrayOf(
            0xFFE4A672.toInt(), 0xFFB86F50.toInt(), 0xFF743F39.toInt(), 0xFF3F2832.toInt(), 0xFF9E2835.toInt(), 0xFFE53B44.toInt(), 0xFFFB922B.toInt(), 0xFFFE8501.toInt(),
            0xFFFF004D.toInt(), 0xFFF6D7BD.toInt(), 0xFFEA8296.toInt(), 0xFFAD2E56.toInt(), 0xFF3E2442.toInt(), 0xFF702A4C.toInt(), 0xFFB04A5A.toInt(), 0xFF97CED1.toInt(),
            0xFF39547B.toInt(), 0x00000000, 0xFFE5E1D9.toInt(), 0xFFCECBC6.toInt(), 0xFF3B3B49.toInt(), 0xFF22354C.toInt(), 0xFF45576D.toInt(), 0xFF66B4CD.toInt(),
            0xFFA8F2E2.toInt(), 0xFF364056.toInt(), 0xFF5C4754.toInt(), 0xFF675363.toInt(), 0xFF8C5A6A.toInt(), 0xFF4D6C6E.toInt(), 0xFF3385A2.toInt(), 0xFF27314A.toInt(),
        ),
    )

    /** Clawd warm 6-color companion ramp (Pixel Lab house palette). */
    val CLAWD: Palette = Palette(
        id = "clawd", name = "Clawd Warm", source = PaletteSource.BUILTIN,
        colors = intArrayOf(
            0xFFFFF1E8.toInt(), 0xFFFFA300.toInt(), 0xFFFF77A8.toInt(), 0xFFAB5236.toInt(), 0xFF5F574F.toInt(), 0xFF1D2B53.toInt(),
        ),
    )

    /** Kimi cool 5-color blue set (Pixel Lab house palette). */
    val KIMI: Palette = Palette(
        id = "kimi", name = "Kimi Blue", source = PaletteSource.BUILTIN,
        colors = intArrayOf(
            0xFFDAF3FF.toInt(), 0xFF29ADFF.toInt(), 0xFF1D2B53.toInt(), 0xFF7E2553.toInt(), 0xFF1B1B2E.toInt(),
        ),
    )

    /** All built-ins in stable, documented order. */
    fun all(): List<Palette> = listOf(PICO8, GAMEBOY, NES54, ENDESGA32, CLAWD, KIMI)

    /** Looks up a built-in palette by [id]; null when unknown. */
    fun byId(id: String): Palette? = all().firstOrNull { it.id == id }
}
