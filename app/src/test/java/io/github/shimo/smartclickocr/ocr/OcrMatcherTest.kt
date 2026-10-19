package io.github.shimo.smartclickocr.ocr

import io.github.shimo.smartclickocr.model.ActionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Exact-match semantics for OCR_CHECK (spec §3.3). */
class OcrMatcherTest {

    @Test
    fun `identical text matches`() {
        assertEquals(ActionResult.StopMatched, OcrMatcher.evaluate("任务完成", "任务完成"))
    }

    @Test
    fun `outer whitespace is ignored on both sides`() {
        assertEquals(ActionResult.StopMatched, OcrMatcher.evaluate("  任务完成\n", "任务完成 "))
        assertEquals(ActionResult.StopMatched, OcrMatcher.evaluate("\t任务完成", "  任务完成  "))
    }

    @Test
    fun `extra characters inside the text do not match`() {
        // "任务已完成" must NOT match "任务完成".
        assertEquals(ActionResult.Continue, OcrMatcher.evaluate("任务已完成", "任务完成"))
    }

    @Test
    fun `missing characters do not match`() {
        assertEquals(ActionResult.Continue, OcrMatcher.evaluate("任务", "任务完成"))
    }

    @Test
    fun `reordered characters do not match`() {
        assertEquals(ActionResult.Continue, OcrMatcher.evaluate("完成都", "成都完成").let { it }.let {
            OcrMatcher.evaluate("成都完成", "完成成都")
        })
    }

    @Test
    fun `whitespace differences are treated as an ocr artefact`() {
        // OCR commonly inserts or drops spaces between Chinese glyphs. Treating that as
        // a mismatch made correctly-recognised text fail the check.
        assertEquals(ActionResult.StopMatched, OcrMatcher.evaluate("任务 完成", "任务完成"))
        assertEquals(ActionResult.StopMatched, OcrMatcher.evaluate("任务完成", "任务 完成"))
        assertEquals(ActionResult.StopMatched, OcrMatcher.evaluate("任 务 完 成", "任务完成"))
    }

    @Test
    fun `full width ascii is folded to half width`() {
        assertEquals(ActionResult.StopMatched, OcrMatcher.evaluate("１２３", "123"))
        assertEquals(ActionResult.StopMatched, OcrMatcher.evaluate("ＡＢＣ", "ABC"))
    }

    @Test
    fun `normalising never drops or reorders characters`() {
        // The strictness that matters stays intact.
        assertEquals(ActionResult.Continue, OcrMatcher.evaluate("任务 已 完成", "任务完成"))
        assertEquals(ActionResult.Continue, OcrMatcher.evaluate("完成 任务", "任务 完成").let {
            OcrMatcher.evaluate("完成任务", "任务完成")
        })
    }

    @Test
    fun `case is significant for latin text`() {
        assertEquals(ActionResult.Continue, OcrMatcher.evaluate("Start", "start"))
    }

    @Test
    fun `no match is a Continue result never an error`() {
        val result = OcrMatcher.evaluate("完全不同的文字", "任务完成")
        assertEquals(ActionResult.Continue, result)
        assertEquals(false, result is ActionResult.Error)
    }

    @Test
    fun `empty target only matches empty recognised text`() {
        assertEquals(ActionResult.StopMatched, OcrMatcher.evaluate("   ", ""))
        assertEquals(ActionResult.Continue, OcrMatcher.evaluate("x", ""))
    }

    @Test
    fun `multiple blocks are joined in reading order`() {
        val blocks = listOf(
            OcrMatcher.TextBlock("第二行", 10, 100, 100, 120),
            OcrMatcher.TextBlock("第一行右", 200, 10, 300, 30),
            OcrMatcher.TextBlock("第一行左", 10, 10, 150, 30)
        )
        val joined = OcrMatcher.joinInReadingOrder(blocks)
        assertEquals("第一行左\n第一行右\n第二行", joined)
    }

    @Test
    fun `joined multiple blocks match a multi-line target exactly`() {
        val blocks = listOf(
            OcrMatcher.TextBlock("任务完成", 0, 0, 50, 10),
            OcrMatcher.TextBlock("恭喜", 0, 20, 50, 30)
        )
        val joined = OcrMatcher.joinInReadingOrder(blocks)
        assertEquals(ActionResult.StopMatched, OcrMatcher.evaluate(joined, "任务完成\n恭喜"))
        // A single-block target must not match a two-block region.
        assertEquals(ActionResult.Continue, OcrMatcher.evaluate(joined, "任务完成"))
    }

    @Test
    fun `empty block list yields empty string`() {
        assertEquals("", OcrMatcher.joinInReadingOrder(emptyList()))
    }

    @Test
    fun `region validation rejects zero size`() {
        assertNotNull(OcrMatcher.validateRegion(10, 10, 10, 20, 1080, 2400))
        assertNotNull(OcrMatcher.validateRegion(10, 10, 20, 10, 1080, 2400))
    }

    @Test
    fun `region validation rejects out of bounds`() {
        assertNotNull(OcrMatcher.validateRegion(-1, 0, 100, 100, 1080, 2400))
        assertNotNull(OcrMatcher.validateRegion(0, 0, 1081, 100, 1080, 2400))
        assertNotNull(OcrMatcher.validateRegion(0, 0, 100, 2401, 1080, 2400))
    }

    @Test
    fun `region validation accepts in bounds region`() {
        assertNull(OcrMatcher.validateRegion(0, 0, 1080, 2400, 1080, 2400))
        assertNull(OcrMatcher.validateRegion(10, 20, 100, 200, 1080, 2400))
    }

    @Test
    fun `block matching finds a short target merged with neighbouring lines`() {
        // Real case: selecting a large single piece of text on screen makes ML Kit group it
        // with the surrounding labels, so the whole text never equals the target even though
        // the target is right there. Matching per block fixes it.
        val blocks = listOf(
            OcrMatcher.TextBlock("订单详情", 0, 0, 100, 40),
            OcrMatcher.TextBlock("请确认以下信息", 0, 50, 400, 90),
            OcrMatcher.TextBlock("完成", 100, 200, 200, 300)
        )
        val whole = OcrMatcher.joinInReadingOrder(blocks)
        assertEquals(ActionResult.Continue, OcrMatcher.evaluate(whole, "完成"))
        assertEquals(ActionResult.StopMatched, OcrMatcher.evaluateBlocks(blocks, whole, "完成"))
    }

    @Test
    fun `block matching still ignores partial and reordered text`() {
        val blocks = listOf(
            OcrMatcher.TextBlock("操作提示信息", 0, 0, 300, 40),
            OcrMatcher.TextBlock("任务已完成", 0, 50, 300, 90)
        )
        val whole = OcrMatcher.joinInReadingOrder(blocks)
        // Exact-match semantics are preserved: no substring or fuzzy matching.
        assertEquals(ActionResult.Continue, OcrMatcher.evaluateBlocks(blocks, whole, "任务完成"))
        assertEquals(ActionResult.Continue, OcrMatcher.evaluateBlocks(blocks, whole, "提示信息"))
        assertEquals(ActionResult.Continue, OcrMatcher.evaluateBlocks(blocks, whole, "完成"))
    }

    @Test
    fun `block matching handles a block containing several lines`() {
        val blocks = listOf(OcrMatcher.TextBlock("订单详情\n完成", 0, 0, 300, 90))
        assertEquals(ActionResult.StopMatched, OcrMatcher.evaluateBlocks(blocks, "订单详情\n完成", "完成"))
    }

    @Test
    fun `block matching ignores a blank target`() {
        val blocks = listOf(OcrMatcher.TextBlock("完成", 0, 0, 10, 10))
        assertEquals(ActionResult.Continue, OcrMatcher.evaluateBlocks(blocks, "完成", ""))
        assertEquals(ActionResult.Continue, OcrMatcher.evaluateBlocks(blocks, "完成", "   "))
    }

    @Test
    fun `block matching normalises the same way as whole-text matching`() {
        val blocks = listOf(OcrMatcher.TextBlock("星 ", 0, 0, 10, 10))
        assertEquals(ActionResult.StopMatched, OcrMatcher.evaluateBlocks(blocks, "星 ", " 星"))
    }
}
