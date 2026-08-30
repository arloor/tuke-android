package com.arloor.tuke.feature.agent

internal sealed interface MarkdownSegment {
    data class Text(val markdown: String) : MarkdownSegment

    data class Table(val table: MarkdownTable) : MarkdownSegment
}

internal data class MarkdownTable(
    val header: List<String>,
    val alignments: List<MarkdownTableAlignment>,
    val rows: List<List<String>>,
)

internal enum class MarkdownTableAlignment {
    START,
    CENTER,
    END,
}

internal fun MarkdownTable.toMarkdownClipboardText(): String = buildString {
    appendMarkdownTableRow(header)
    append('\n')
    appendMarkdownTableRow(alignments.map(MarkdownTableAlignment::toMarkdownDelimiter))
    rows.forEach { row ->
        append('\n')
        appendMarkdownTableRow(row)
    }
}

private fun StringBuilder.appendMarkdownTableRow(cells: List<String>) {
    cells.joinTo(
        buffer = this,
        separator = " | ",
        prefix = "| ",
        postfix = " |",
    ) { cell -> cell.replace("\r\n", "<br>").replace("\n", "<br>") }
}

private fun MarkdownTableAlignment.toMarkdownDelimiter(): String = when (this) {
    MarkdownTableAlignment.START -> "---"
    MarkdownTableAlignment.CENTER -> ":---:"
    MarkdownTableAlignment.END -> "---:"
}

/**
 * Splits top-level GFM tables out of a Markdown document so they can be rendered by a
 * size-stable native widget instead of a replacement span inside one large TextView.
 */
internal fun splitMarkdownSegments(
    markdown: String,
    streaming: Boolean = false,
): List<MarkdownSegment> {
    if ('|' !in markdown) {
        return listOf(MarkdownSegment.Text(markdown))
    }

    val normalized = markdown
        .replace("\r\n", "\n")
        .replace('\r', '\n')
    val lines = normalized.split('\n')
    val segments = mutableListOf<MarkdownSegment>()
    val pendingText = mutableListOf<String>()
    var activeFence: MarkdownFence? = null
    var foundTable = false
    var index = 0

    fun flushText() {
        val text = pendingText.joinToString("\n").trim('\n')
        if (text.isNotBlank()) {
            segments += MarkdownSegment.Text(text)
        }
        pendingText.clear()
    }

    while (index < lines.size) {
        val line = lines[index]
        val fence = activeFence
        if (fence != null) {
            pendingText += line
            if (isClosingFence(line, fence)) {
                activeFence = null
            }
            index++
            continue
        }

        val opening = openingFence(line)
        if (opening != null) {
            activeFence = opening
            pendingText += line
            index++
            continue
        }

        val header = parseTableRow(line)
        val delimiterLine = lines.getOrNull(index + 1)
        val delimiter = delimiterLine?.let(::parseTableRow)
        val alignments = delimiter?.let {
            parseDelimiterAlignments(
                delimiter = it,
                expectedColumns = header.cells.size,
            )
        }
        val isTableStart =
            !isIndentedCode(line) &&
                delimiterLine != null &&
                !isIndentedCode(delimiterLine) &&
                header.hasDelimiter &&
                header.cells.isNotEmpty() &&
                alignments != null

        if (!isTableStart) {
            pendingText += line
            index++
            continue
        }

        flushText()
        foundTable = true
        val columnCount = header.cells.size
        val rows = mutableListOf<List<String>>()
        var bodyIndex = index + 2
        while (bodyIndex < lines.size) {
            val bodyLine = lines[bodyIndex]
            if (bodyLine.isBlank()) {
                break
            }

            val bodyRow = parseTableRow(bodyLine)
            val isIncompleteTrailingRow = streaming && bodyIndex == lines.lastIndex
            if (!bodyRow.hasDelimiter && !isIncompleteTrailingRow) {
                break
            }
            rows += normalizeCells(bodyRow.cells, columnCount)
            bodyIndex++
        }

        segments += MarkdownSegment.Table(
            MarkdownTable(
                header = normalizeCells(header.cells, columnCount),
                alignments = alignments,
                rows = rows,
            ),
        )
        index = bodyIndex
    }

    flushText()
    return if (foundTable) segments else listOf(MarkdownSegment.Text(markdown))
}

private data class ParsedTableRow(
    val cells: List<String>,
    val hasDelimiter: Boolean,
)

private data class MarkdownFence(
    val marker: Char,
    val length: Int,
)

private fun parseTableRow(line: String): ParsedTableRow {
    val input = line.trim()
    val cells = mutableListOf(StringBuilder())
    var hasDelimiter = false
    var codeFenceLength = 0
    var index = 0

    while (index < input.length) {
        val character = input[index]
        if (character == '\\' && index + 1 < input.length) {
            cells.last().append(character)
            cells.last().append(input[index + 1])
            index += 2
            continue
        }

        if (character == '`') {
            var runEnd = index + 1
            while (runEnd < input.length && input[runEnd] == '`') {
                runEnd++
            }
            val runLength = runEnd - index
            codeFenceLength = when {
                codeFenceLength == 0 -> runLength
                codeFenceLength == runLength -> 0
                else -> codeFenceLength
            }
            cells.last().append(input, index, runEnd)
            index = runEnd
            continue
        }

        if (character == '|' && codeFenceLength == 0) {
            hasDelimiter = true
            cells += StringBuilder()
        } else {
            cells.last().append(character)
        }
        index++
    }

    val values = cells.mapTo(mutableListOf()) { it.toString().trim() }
    if (hasDelimiter && values.size > 1 && values.first().isEmpty()) {
        values.removeAt(0)
    }
    if (hasDelimiter && values.size > 1 && values.last().isEmpty()) {
        values.removeAt(values.lastIndex)
    }
    return ParsedTableRow(values, hasDelimiter)
}

private fun parseDelimiterAlignments(
    delimiter: ParsedTableRow,
    expectedColumns: Int,
): List<MarkdownTableAlignment>? {
    if (!delimiter.hasDelimiter || delimiter.cells.size != expectedColumns) {
        return null
    }
    return delimiter.cells.map { cell ->
        val marker = cell.trim()
        if (!TABLE_DELIMITER.matches(marker)) {
            return null
        }
        when {
            marker.startsWith(':') && marker.endsWith(':') -> MarkdownTableAlignment.CENTER
            marker.endsWith(':') -> MarkdownTableAlignment.END
            else -> MarkdownTableAlignment.START
        }
    }
}

private fun normalizeCells(cells: List<String>, columnCount: Int): List<String> =
    List(columnCount) { columnIndex -> cells.getOrElse(columnIndex) { "" }.trim() }

private fun openingFence(line: String): MarkdownFence? {
    val indent = line.takeWhile { it == ' ' }.length
    if (indent > 3 || line.startsWith('\t')) {
        return null
    }
    val content = line.drop(indent)
    val marker = content.firstOrNull()?.takeIf { it == '`' || it == '~' } ?: return null
    val length = content.takeWhile { it == marker }.length
    return length.takeIf { it >= 3 }?.let { MarkdownFence(marker, it) }
}

private fun isClosingFence(line: String, fence: MarkdownFence): Boolean {
    val indent = line.takeWhile { it == ' ' }.length
    if (indent > 3 || line.startsWith('\t')) {
        return false
    }
    val content = line.drop(indent)
    val length = content.takeWhile { it == fence.marker }.length
    return length >= fence.length && content.drop(length).isBlank()
}

private fun isIndentedCode(line: String): Boolean =
    line.startsWith('\t') || line.takeWhile { it == ' ' }.length >= 4

private val TABLE_DELIMITER = Regex("^:?-+:?$")
