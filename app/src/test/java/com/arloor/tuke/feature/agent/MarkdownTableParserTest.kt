package com.arloor.tuke.feature.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTableParserTest {

    @Test
    fun table_is_split_from_surrounding_markdown() {
        val segments = splitMarkdownSegments(
            """
            表格之前

            | 名称 | 涨幅 | 备注 |
            | :--- | ---: | :---: |
            | 平安银行 | 1.2% | **关注** |

            表格之后
            """.trimIndent(),
        )

        assertEquals(3, segments.size)
        assertEquals("表格之前", (segments[0] as MarkdownSegment.Text).markdown)
        val table = (segments[1] as MarkdownSegment.Table).table
        assertEquals(listOf("名称", "涨幅", "备注"), table.header)
        assertEquals(
            listOf(
                MarkdownTableAlignment.START,
                MarkdownTableAlignment.END,
                MarkdownTableAlignment.CENTER,
            ),
            table.alignments,
        )
        assertEquals(listOf(listOf("平安银行", "1.2%", "**关注**")), table.rows)
        assertEquals("表格之后", (segments[2] as MarkdownSegment.Text).markdown)
    }

    @Test
    fun escaped_and_code_span_pipes_stay_inside_a_cell() {
        val segments = splitMarkdownSegments(
            """
            | 名称 | 说明 |
            | --- | --- |
            | A | a \| b |
            | B | `x|y` |
            """.trimIndent(),
        )

        val table = (segments.single() as MarkdownSegment.Table).table
        assertEquals("a \\| b", table.rows[0][1])
        assertEquals("`x|y`", table.rows[1][1])
    }

    @Test
    fun table_is_copied_as_gfm_markdown_with_alignment() {
        val table = MarkdownTable(
            header = listOf("名称", "涨幅", "备注"),
            alignments = listOf(
                MarkdownTableAlignment.START,
                MarkdownTableAlignment.END,
                MarkdownTableAlignment.CENTER,
            ),
            rows = listOf(listOf("平安银行", "1.2%", "**关注**")),
        )

        assertEquals(
            """
            | 名称 | 涨幅 | 备注 |
            | --- | ---: | :---: |
            | 平安银行 | 1.2% | **关注** |
            """.trimIndent(),
            table.toMarkdownClipboardText(),
        )
    }

    @Test
    fun streaming_trailing_row_is_kept_in_the_table_before_its_first_pipe_arrives() {
        val markdown = """
            | 名称 | 说明 |
            | --- | --- |
            正在生成
        """.trimIndent()

        val streamingTable = (
            splitMarkdownSegments(markdown, streaming = true).single() as MarkdownSegment.Table
            ).table
        assertEquals(listOf("正在生成", ""), streamingTable.rows.single())

        val completed = splitMarkdownSegments(markdown, streaming = false)
        assertEquals(2, completed.size)
        assertTrue(completed[0] is MarkdownSegment.Table)
        assertEquals("正在生成", (completed[1] as MarkdownSegment.Text).markdown)
    }

    @Test
    fun table_syntax_inside_a_fenced_code_block_is_not_split() {
        val markdown = """
            ```markdown
            | 名称 | 涨幅 |
            | --- | ---: |
            | A | 1% |
            ```
        """.trimIndent()

        assertEquals(listOf(MarkdownSegment.Text(markdown)), splitMarkdownSegments(markdown))
    }

    @Test
    fun incomplete_delimiter_is_plain_markdown_until_it_becomes_valid() {
        val markdown = """
            | 名称 | 涨幅 |
            | --- | : |
        """.trimIndent()

        assertEquals(listOf(MarkdownSegment.Text(markdown)), splitMarkdownSegments(markdown))
    }
}
