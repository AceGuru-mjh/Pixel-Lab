package com.pixellab.core.batch

import com.pixellab.core.engine.PixelEngine
import com.pixellab.core.model.PixelPoint
import com.pixellab.core.model.SpriteProject

/**
 * One typed drawing operation inside a [DrawingBatch].
 *
 * The sealed hierarchy mirrors the primitive engine draws an agent already
 * knows from the single-op tools — pixel, line, rect, circle, stroke, fill,
 * color replace and erase — so a batch is exactly "the calls I would have
 * made one at a time, issued together". Colors are ARGB integers; parsing
 * from wire formats happens in the MCP layer before batch construction.
 *
 * Ops are immutable value types; validation is centralized in
 * [DrawingBatch.validate] so a batch is checked *before* the first engine
 * call, never partially applied due to a parameter error.
 */
sealed class BatchOp {

    /** Paints a single pixel (out-of-bounds cells are reported, not drawn). */
    data class Pixel(val x: Int, val y: Int, val argb: Int) : BatchOp()

    /** Bresenham line with thickness (parallel-offset merge). */
    data class Line(
        val x0: Int,
        val y0: Int,
        val x1: Int,
        val y1: Int,
        val argb: Int,
        val thickness: Int = 1,
    ) : BatchOp()

    /** Rectangle by top-left corner and extent, filled or outlined. */
    data class Rect(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val argb: Int,
        val filled: Boolean = false,
    ) : BatchOp()

    /** Midpoint circle around a center, filled or 8-way outline. */
    data class Circle(
        val cx: Int,
        val cy: Int,
        val radius: Int,
        val argb: Int,
        val filled: Boolean = false,
    ) : BatchOp()

    /** Free polyline through [points] with a brush thickness. */
    data class Stroke(
        val points: List<PixelPoint>,
        val argb: Int,
        val thickness: Int = 1,
    ) : BatchOp()

    /** Flood fill seeded at one cell with a channel tolerance. */
    data class Fill(
        val x: Int,
        val y: Int,
        val argb: Int,
        val tolerance: Int = 0,
    ) : BatchOp()

    /** Replaces every cel pixel within tolerance of one color with another. */
    data class Replace(
        val from: Int,
        val to: Int,
        val tolerance: Int = 0,
    ) : BatchOp()

    /** Erases (sets fully transparent) a list of cells. */
    data class Erase(val points: List<PixelPoint>) : BatchOp()
}

/**
 * Validation failure of one batch operation: the failing op [index] and a
 * human-readable [reason]. The message always carries the op index so an
 * agent can pinpoint the malformed entry without counting.
 */
class BatchValidationException(
    val index: Int,
    val opKind: String,
    reason: String,
) : IllegalArgumentException("op[$index] ($opKind): $reason")

/**
 * Result of applying one [BatchOp]: the op ran and changed pixels, or ran
 * and changed nothing (no-op), with the engine's own history label.
 *
 * No-op detection relies on the engine's structural-equality discipline:
 * an operation that does not change the active cel returns the *same*
 * project reference it was given, so `applied == (result !== before)`.
 */
data class OpOutcome(
    val index: Int,
    val op: BatchOp,
    val applied: Boolean,
    val label: String,
)

/**
 * Result of a whole batch: the final project plus per-op outcomes.
 *
 * [outcomes] has one entry per op, in batch order. [appliedCount] is the
 * number of ops that actually changed pixels; [historyEntriesAdded] counts
 * the undo records the engine created (one per applied op) — a batch that
 * fully no-ops adds zero history and therefore cannot disturb undo state.
 */
class BatchResult(
    val project: SpriteProject,
    val outcomes: List<OpOutcome>,
) {
    val appliedCount: Int get() = outcomes.count { it.applied }

    val historyEntriesAdded: Int get() = appliedCount

    /** First validation-safe rendering for logs and tests. */
    override fun toString(): String =
        "BatchResult(${outcomes.size} ops, $appliedCount applied)"
}

/**
 * Validation-first batch executor over [PixelEngine].
 *
 * Agents drawing over MCP pay one network round trip per primitive. A
 * 20-primitive sprite sketch means 20 sequential calls — 20 chances for a
 * mid-sequence parameter error to leave the canvas half-drawn with no
 * atomic way back. `DrawingBatch` closes that gap with two guarantees:
 *
 *  * **Validation-first** — [validate] checks *every* op (geometry, brush
 *    limits, fill seeds, layer writability) against the project before the
 *    first engine call. A bad op therefore never produces a partially
 *    applied batch; [BatchValidationException] names the offending index.
 *  * **Per-op accounting** — [apply] reports each op as applied or no-op,
 *    and the caller diffs the composite before/after for the aggregate
 *    change summary.
 *
 * Each applied op creates its own engine undo entry (labels keep the batch
 * shape: `batch:<kind>`). Rollback of a whole batch is one
 * checkpoint rollback, or N undos.
 *
 * Bounds policy matches the single-op tools: out-of-bounds stroke/erase
 * points and degenerate geometry are hard validation errors — a batch is
 * composed deliberately, so the agent should notice a mistake instead of
 * silently losing pixels. Primitives that merely overhang an edge are
 * clipped by the engine exactly like the single-op tools.
 *
 * Pure and stateless — all state lives in the engine and the project.
 */
object DrawingBatch {

    /** Upper bound for ops in one batch (DoS guard). */
    const val MAX_OPS: Int = 64

    /** Brush thickness range accepted for line and stroke ops. */
    const val MAX_THICKNESS: Int = 16

    /**
     * Validates every op against [project] without touching the engine.
     *
     * @throws BatchValidationException naming the first failing op index.
     */
    fun validate(ops: List<BatchOp>, project: SpriteProject) {
        require(ops.size <= MAX_OPS) {
            "batch has ${ops.size} ops (max $MAX_OPS); split into several draw_batch calls"
        }
        val layer = project.activeLayer
        require(!layer.locked) {
            "active layer '${layer.name}' (id=${layer.id}) is locked; unlock it (layer_set_locked) before drawing"
        }
        for ((index, op) in ops.withIndex()) {
            when (op) {
                is BatchOp.Pixel -> {
                    if (op.x < 0 || op.y < 0 || op.x >= project.width || op.y >= project.height) {
                        throw BatchValidationException(
                            index, "pixel",
                            "coordinate (${op.x}, ${op.y}) outside ${project.width}x${project.height} canvas",
                        )
                    }
                }
                is BatchOp.Line -> {
                    if (op.thickness < 1 || op.thickness > MAX_THICKNESS) {
                        throw BatchValidationException(
                            index, "line",
                            "thickness ${op.thickness} outside 1..$MAX_THICKNESS",
                        )
                    }
                    spanGuard(index, "line", op.x0, op.y0, op.x1, op.y1, project.width, project.height)
                }
                is BatchOp.Rect -> {
                    if (op.width < 1 || op.height < 1) {
                        throw BatchValidationException(
                            index, "rect",
                            "size ${op.width}x${op.height} must have edges >= 1",
                        )
                    }
                    spanGuard(
                        index, "rect", op.x, op.y, op.x + op.width - 1, op.y + op.height - 1,
                        project.width, project.height,
                    )
                }
                is BatchOp.Circle -> {
                    if (op.radius < 0) {
                        throw BatchValidationException(
                            index, "circle", "radius ${op.radius} must be >= 0",
                        )
                    }
                    val reach = op.radius + 1
                    spanGuard(
                        index, "circle",
                        op.cx - reach, op.cy - reach, op.cx + reach, op.cy + reach,
                        project.width, project.height,
                    )
                }
                is BatchOp.Stroke -> {
                    if (op.points.isEmpty()) {
                        throw BatchValidationException(index, "stroke", "points list is empty")
                    }
                    if (op.points.size > MAX_OPS * 8) {
                        throw BatchValidationException(
                            index, "stroke",
                            "stroke has ${op.points.size} points (max ${MAX_OPS * 8})",
                        )
                    }
                    if (op.thickness < 1 || op.thickness > MAX_THICKNESS) {
                        throw BatchValidationException(
                            index, "stroke",
                            "thickness ${op.thickness} outside 1..$MAX_THICKNESS",
                        )
                    }
                    for (p in op.points) {
                        if (p.x < 0 || p.y < 0 || p.x >= project.width || p.y >= project.height) {
                            throw BatchValidationException(
                                index, "stroke",
                                "point (${p.x}, ${p.y}) outside ${project.width}x${project.height} canvas",
                            )
                        }
                    }
                }
                is BatchOp.Fill -> {
                    if (op.tolerance < 0) {
                        throw BatchValidationException(
                            index, "fill", "tolerance ${op.tolerance} must be >= 0",
                        )
                    }
                    if (op.x < 0 || op.y < 0 || op.x >= project.width || op.y >= project.height) {
                        throw BatchValidationException(
                            index, "fill",
                            "seed (${op.x}, ${op.y}) outside ${project.width}x${project.height} canvas",
                        )
                    }
                }
                is BatchOp.Replace -> {
                    if (op.tolerance < 0) {
                        throw BatchValidationException(
                            index, "replace", "tolerance ${op.tolerance} must be >= 0",
                        )
                    }
                }
                is BatchOp.Erase -> {
                    if (op.points.isEmpty()) {
                        throw BatchValidationException(index, "erase", "points list is empty")
                    }
                    if (op.points.size > MAX_OPS * 8) {
                        throw BatchValidationException(
                            index, "erase",
                            "erase has ${op.points.size} points (max ${MAX_OPS * 8})",
                        )
                    }
                    for (p in op.points) {
                        if (p.x < 0 || p.y < 0 || p.x >= project.width || p.y >= project.height) {
                            throw BatchValidationException(
                                index, "erase",
                                "point (${p.x}, ${p.y}) outside ${project.width}x${project.height} canvas",
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Applies every op in order, starting from [project]. Call [validate]
     * first; calling apply on an unvalidated batch may still throw from the
     * engine, and a mid-batch engine failure leaves earlier ops applied
     * (they are individually undoable — hence the checkpoint discipline).
     *
     * @return per-op outcomes plus the final project.
     */
    fun apply(ops: List<BatchOp>, project: SpriteProject, engine: PixelEngine): BatchResult {
        val outcomes = ArrayList<OpOutcome>(ops.size)
        var current = project
        for ((index, op) in ops.withIndex()) {
            val before = current
            current = when (op) {
                is BatchOp.Pixel -> engine.drawPixel(current, op.x, op.y, op.argb)
                is BatchOp.Line -> engine.drawLine(current, op.x0, op.y0, op.x1, op.y1, op.argb, op.thickness)
                is BatchOp.Rect -> engine.drawRect(current, op.x, op.y, op.width, op.height, op.argb, op.filled)
                is BatchOp.Circle -> engine.drawCircle(current, op.cx, op.cy, op.radius, op.argb, op.filled)
                is BatchOp.Stroke -> engine.drawStroke(current, op.points, op.argb, op.thickness)
                is BatchOp.Fill -> engine.fill(current, op.x, op.y, op.argb, op.tolerance)
                is BatchOp.Replace -> engine.replaceColor(current, op.from, op.to, op.tolerance)
                is BatchOp.Erase -> engine.erasePixels(current, op.points)
            }
            val applied = current !== before
            outcomes.add(OpOutcome(index, op, applied, labelOf(op)))
        }
        return BatchResult(current, outcomes)
    }

    /**
     * Validates then applies in one call — the composite the MCP tool uses.
     *
     * @throws BatchValidationException from [validate] before any mutation.
     */
    fun validateAndApply(ops: List<BatchOp>, project: SpriteProject, engine: PixelEngine): BatchResult {
        validate(ops, project)
        return apply(ops, project, engine)
    }

    /** History label for one op kind (exposed for tier layers that report
     *  outcomes without applying, e.g. dry-run batches). */
    fun labelOf(op: BatchOp): String = when (op) {
        is BatchOp.Pixel -> "batch:pixel"
        is BatchOp.Line -> "batch:line"
        is BatchOp.Rect -> "batch:rect"
        is BatchOp.Circle -> "batch:circle"
        is BatchOp.Stroke -> "batch:stroke"
        is BatchOp.Fill -> "batch:fill"
        is BatchOp.Replace -> "batch:replace"
        is BatchOp.Erase -> "batch:erase"
    }

    /**
     * Rejects geometry whose bounding box cannot overlap the canvas at all:
     * a primitive fully outside the canvas can never touch a pixel, so it
     * is a caller error, not a clip. Primitives that merely overhang an
     * edge (partial overlap) are fine — the engine clips those exactly
     * like the single-op tools.
     */
    private fun spanGuard(index: Int, kind: String, minX: Int, minY: Int, maxX: Int, maxY: Int, width: Int, height: Int) {
        val noHorizontalOverlap = maxX < 0 || minX >= width
        val noVerticalOverlap = maxY < 0 || minY >= height
        if (noHorizontalOverlap || noVerticalOverlap) {
            throw BatchValidationException(
                index, kind,
                "span ($minX,$minY)-($maxX,$maxY) lies entirely outside the ${width}x${height} canvas",
            )
        }
    }
}
