package com.arloor.tuke.feature.agent

import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Delimited
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.LinkReferenceDefinition
import org.commonmark.node.ListBlock
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Text

internal data class MarkdownNodeBlock(
    val node: Node,
    val fingerprint: Long,
)

internal data class MarkdownCodeBlock(
    val code: String,
    val language: String,
)

internal data class MarkdownMermaidBlock(
    val source: String,
)

internal fun MarkdownNodeBlock.mermaidBlockOrNull(
    streaming: Boolean = false,
): MarkdownMermaidBlock? {
    if (streaming) return null
    val fenced = node as? FencedCodeBlock ?: return null
    val language = fenced.info
        ?.trim()
        ?.substringBefore(' ')
        ?.lowercase()
        ?: return null
    if (language != "mermaid") return null
    return MarkdownMermaidBlock(source = fenced.literal.removeSuffix("\n"))
}

internal fun MarkdownNodeBlock.codeBlockOrNull(): MarkdownCodeBlock? = when (node) {
    is FencedCodeBlock -> MarkdownCodeBlock(
        code = node.literal.removeSuffix("\n"),
        language = node.info
            ?.trim()
            ?.substringBefore(' ')
            ?.takeIf(String::isNotBlank)
            ?: "code",
    )
    is IndentedCodeBlock -> MarkdownCodeBlock(
        code = node.literal.removeSuffix("\n"),
        language = "code",
    )
    else -> null
}

/** Returns independently renderable top-level CommonMark blocks in document order. */
internal fun markdownNodeBlocks(document: Node): List<MarkdownNodeBlock> = buildList {
    var node = document.firstChild
    while (node != null) {
        val next = node.next
        // Render each block in isolation. Markwon otherwise observes the original next sibling
        // and appends inter-block newlines inside the TextView, defeating stable Compose spacing.
        node.unlink()
        if (node !is LinkReferenceDefinition) {
            add(
                MarkdownNodeBlock(
                    node = node,
                    fingerprint = markdownNodeFingerprint(node),
                ),
            )
        }
        node = next
    }
}

/**
 * Computes a stable structural fingerprint without rendering the node. When streaming appends
 * content only the changing block receives a new fingerprint; completed blocks retain their
 * remembered rendered text and TextView instance.
 */
internal fun markdownNodeFingerprint(root: Node): Long {
    var hash = HASH_SEED

    fun mix(value: Int) {
        hash = (hash xor value.toLong()) * HASH_MULTIPLIER
    }

    fun mix(value: String?) {
        if (value == null) {
            mix(NULL_MARKER)
            return
        }
        mix(value.length)
        value.forEach { mix(it.code) }
        mix(STRING_END_MARKER)
    }

    fun visit(node: Node) {
        mix(node.javaClass.name)
        when (node) {
            is Text -> mix(node.literal)
            is Code -> mix(node.literal)
            is FencedCodeBlock -> {
                mix(node.fenceChar.code)
                mix(node.fenceLength)
                mix(node.fenceIndent)
                mix(node.info)
                mix(node.literal)
            }
            is IndentedCodeBlock -> mix(node.literal)
            is HtmlInline -> mix(node.literal)
            is HtmlBlock -> mix(node.literal)
            is Heading -> mix(node.level)
            is Link -> {
                mix(node.destination)
                mix(node.title)
            }
            is Image -> {
                mix(node.destination)
                mix(node.title)
            }
            is OrderedList -> {
                mix(node.startNumber)
                mix(node.delimiter.code)
                mix(if (node.isTight) 1 else 0)
            }
            is BulletList -> {
                mix(node.bulletMarker.code)
                mix(if (node.isTight) 1 else 0)
            }
            is ListBlock -> mix(if (node.isTight) 1 else 0)
            is LinkReferenceDefinition -> {
                mix(node.label)
                mix(node.destination)
                mix(node.title)
            }
            is Delimited -> {
                mix(node.openingDelimiter)
                mix(node.closingDelimiter)
            }
        }

        var child = node.firstChild
        while (child != null) {
            visit(child)
            child = child.next
        }
        mix(NODE_END_MARKER)
    }

    visit(root)
    return hash
}

private const val HASH_SEED = 1_125_899_906_842_597L
private const val HASH_MULTIPLIER = 1_099_511_628_211L
private const val NULL_MARKER = -1
private const val STRING_END_MARKER = -2
private const val NODE_END_MARKER = -3
