package io.github.shimo.smartclickocr.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory run log (spec §9.3). Bounded so a long run cannot exhaust memory.
 */
object RunLog {

    private const val MAX_LINES = 200

    data class Line(val time: String, val text: String)

    private val _lines = MutableStateFlow<List<Line>>(emptyList())
    val lines: StateFlow<List<Line>> = _lines.asStateFlow()

    private val formatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    @Synchronized
    fun add(message: String) {
        val line = Line(formatter.format(Date()), message)
        val current = _lines.value
        val next = if (current.size >= MAX_LINES) {
            current.drop(current.size - MAX_LINES + 1) + line
        } else {
            current + line
        }
        _lines.value = next
    }

    @Synchronized
    fun clear() {
        _lines.value = emptyList()
    }

    fun recent(limit: Int = 50): List<Line> = _lines.value.takeLast(limit).reversed()

    fun latestText(): String = _lines.value.lastOrNull()?.text ?: ""
}
