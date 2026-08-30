package com.arloor.tuke.feature.agent

import org.commonmark.parser.Parser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MarkdownBlockParserTest {
    private val parser = Parser.builder().build()

    @Test
    fun appending_to_last_block_keeps_completed_block_fingerprint() {
        val before = blocks("第一段已经完成。\n\n第二段")
        val after = blocks("第一段已经完成。\n\n第二段继续增长")

        assertEquals(2, before.size)
        assertEquals(2, after.size)
        assertEquals(before[0].fingerprint, after[0].fingerprint)
        assertNotEquals(before[1].fingerprint, after[1].fingerprint)
    }

    @Test
    fun starting_a_new_block_leaves_all_previous_blocks_stable() {
        val before = blocks("# 标题\n\n正文")
        val after = blocks("# 标题\n\n正文\n\n- 新列表项")

        assertEquals(2, before.size)
        assertEquals(3, after.size)
        assertEquals(
            before.map { it.fingerprint },
            after.take(2).map { it.fingerprint },
        )
    }

    @Test
    fun list_items_share_one_top_level_render_block() {
        val blocks = blocks("1. 第一项\n2. 第二项\n\n列表之后")

        assertEquals(2, blocks.size)
        assertEquals("OrderedList", blocks[0].node.javaClass.simpleName)
        assertEquals("Paragraph", blocks[1].node.javaClass.simpleName)
    }

    @Test
    fun top_level_blocks_are_detached_before_independent_rendering() {
        val blocks = blocks("第一段\n\n第二段")

        blocks.forEach { block ->
            assertNull(block.node.parent)
            assertNull(block.node.previous)
            assertNull(block.node.next)
        }
    }

    @Test
    fun structural_attributes_participate_in_fingerprint() {
        assertNotEquals(
            blocks("# 标题").single().fingerprint,
            blocks("## 标题").single().fingerprint,
        )
        assertNotEquals(
            blocks("```kotlin\nval a = 1\n```").single().fingerprint,
            blocks("```kotlin\nval a = 2\n```").single().fingerprint,
        )
        assertNotEquals(
            blocks("[链接](https://example.com/a)").single().fingerprint,
            blocks("[链接](https://example.com/b)").single().fingerprint,
        )
    }

    @Test
    fun fenced_and_indented_code_blocks_expose_toolbar_metadata() {
        assertEquals(
            MarkdownCodeBlock(code = "val answer = 42", language = "kotlin"),
            blocks("```kotlin title=sample\nval answer = 42\n```").single().codeBlockOrNull(),
        )
        assertEquals(
            MarkdownCodeBlock(code = "indentedCode()", language = "code"),
            blocks("    indentedCode()").single().codeBlockOrNull(),
        )
        assertNull(blocks("普通段落").single().codeBlockOrNull())
    }

    @Test
    fun completed_mermaid_fence_is_exposed_as_diagram() {
        assertEquals(
            MarkdownMermaidBlock("flowchart TD\n  A --> B"),
            blocks("```mermaid\nflowchart TD\n  A --> B\n```")
                .single()
                .mermaidBlockOrNull(),
        )
        assertEquals(
            MarkdownMermaidBlock("sequenceDiagram\n  Alice->>Bob: Hello"),
            blocks("```MERMAID title=example\nsequenceDiagram\n  Alice->>Bob: Hello\n```")
                .single()
                .mermaidBlockOrNull(),
        )
    }

    @Test
    fun streaming_or_non_mermaid_fence_stays_a_code_block() {
        assertNull(
            blocks("```mermaid\nflowchart TD\n  A --> B")
                .single()
                .mermaidBlockOrNull(streaming = true),
        )
        assertNull(
            blocks("```kotlin\nval answer = 42\n```")
                .single()
                .mermaidBlockOrNull(),
        )
    }

    private fun blocks(markdown: String): List<MarkdownNodeBlock> =
        markdownNodeBlocks(parser.parse(markdown))
}
