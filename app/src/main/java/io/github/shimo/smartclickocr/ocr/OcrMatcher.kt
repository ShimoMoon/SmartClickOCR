package io.github.shimo.smartclickocr.ocr

import io.github.shimo.smartclickocr.model.ActionResult

/**
 * Text extraction and exact-matching rules for OCR_CHECK (spec §3.3).
 *
 * Matching is deliberately strict:
 *  - only leading/trailing whitespace is trimmed, on both sides;
 *  - nothing is removed from *inside* the strings, no reordering, no fuzzy matching;
 *  - so "任务完成" does NOT match "任务已完成".
 *
 * When a region yields several text blocks they are concatenated in screen reading
 * order (top-to-bottom, then left-to-right) using [BLOCK_SEPARATOR].
 */
object OcrMatcher {

    /** Separator used when joining multiple detected blocks. */
    const val BLOCK_SEPARATOR = "\n"

    /** A recognised text block, already converted to screen coordinates. */
    data class TextBlock(
        val text: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    )

    /**
     * Concatenates blocks in screen reading order.
     * Rows are bucketed by vertical proximity so items on the same visual line are
     * ordered left-to-right; otherwise the block order is top-to-bottom.
     */
    fun joinInReadingOrder(blocks: List<TextBlock>): String {
        if (blocks.isEmpty()) return ""
        val sorted = blocks.sortedWith(
            compareBy<TextBlock> { it.top }.thenBy { it.left }
        )
        return sorted.joinToString(BLOCK_SEPARATOR) { it.text.trim() }
            .trim()
    }

    /**
     * True when [recognised] matches [target] exactly.
     *
     * Only two, strictly non-semantic transformations are applied to both sides:
     *  - remove **all** whitespace, because OCR frequently inserts or drops spaces
     *    between Chinese glyphs and that is not a different string to a human;
     *  - fold full-width ASCII (Ａ-Ｚ, ０-９, ：) to half width, another pure OCR artefact.
     * Characters themselves are never dropped, reordered or fuzzy-matched, so
     * "任务完成" still does NOT match "任务已完成".
     */
    fun isExactMatch(recognised: String, target: String): Boolean {
        return normalizeForCompare(recognised) == normalizeForCompare(target)
    }

    /** Strips whitespace and folds full-width ASCII; deterministic and order-preserving. */
    fun normalizeForCompare(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            when {
                ch.isWhitespace() -> Unit
                ch.code in 0xFF01..0xFF5E -> sb.append((ch.code - 0xFEE0).toChar())
                ch.code == 0x3000 -> Unit // ideographic space
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    /**
     * Converts recognised text into an [ActionResult]:
     *  - exact match  -> [ActionResult.StopMatched]
     *  - no match     -> [ActionResult.Continue] (a normal outcome, never an error)
     */
    fun evaluate(recognised: String, target: String): ActionResult =
        if (isExactMatch(recognised, target)) ActionResult.StopMatched else ActionResult.Continue

    /**
     * Evaluates a region using BOTH the concatenated text and each individual block.
     *
     * This matters for short targets. ML Kit groups whatever it sees into lines, so a
     * region containing one large piece of text plus neighbouring labels produced a single
     * merged string like "订单详情\n已支付\n完成" — the target "完成" was present but
     * the concatenation could never equal it. Matching any single block fixes that
     * while staying an exact comparison (no fuzzy matching).
     */
    fun evaluateBlocks(blocks: List<TextBlock>, recognised: String, target: String): ActionResult {
        val wanted = normalizeForCompare(target)
        if (wanted.isEmpty()) return ActionResult.Continue
        if (normalizeForCompare(recognised) == wanted) return ActionResult.StopMatched
        // A block may itself contain several lines; check each line of each block too.
        for (block in blocks) {
            if (normalizeForCompare(block.text) == wanted) return ActionResult.StopMatched
            for (line in block.text.split('\n')) {
                if (normalizeForCompare(line) == wanted) return ActionResult.StopMatched
            }
        }
        return ActionResult.Continue
    }

    /**
     * Validates a region against the capture bitmap size.
     * @return null when valid, otherwise a user-facing reason.
     */
    fun validateRegion(
        left: Int, top: Int, right: Int, bottom: Int,
        captureWidth: Int, captureHeight: Int
    ): String? {
        if (right <= left || bottom <= top) return "识别区域的宽度和高度必须大于 0"
        if (left < 0 || top < 0) return "识别区域超出屏幕范围（左上角为负）"
        if (right > captureWidth || bottom > captureHeight) {
            return "识别区域超出截图范围（截图 ${captureWidth}×${captureHeight}）"
        }
        return null
    }
}
