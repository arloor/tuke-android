package com.arloor.tuke.feature.agent

import android.content.Context
import android.graphics.Typeface
import android.text.Layout
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.util.TypedValue
import android.view.Gravity
import androidx.appcompat.widget.AppCompatTextView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.arloor.tuke.ui.Primary
import com.arloor.tuke.ui.TextPrimary
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
internal fun rememberMarkdownRenderer(): Markwon {
    val context = LocalContext.current
    return remember(context.applicationContext) {
        val density = context.resources.displayMetrics.density
        Markwon.builder(context.applicationContext)
            .usePlugin(object : AbstractMarkwonPlugin() {
                override fun configureTheme(builder: MarkwonTheme.Builder) {
                    builder
                        // Markdown 默认标题相对正文过大；聊天正文需要更平缓的层级。
                        .headingTextSizeMultipliers(
                            floatArrayOf(1.50f, 1.31f, 1.16f, 1.08f, 1.0f, 1.0f),
                        )
                        .headingTypeface(Typeface.create("sans-serif", Typeface.BOLD))
                        .headingBreakHeight(density.roundToInt().coerceAtLeast(1))
                }
            })
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TablePlugin.create(context.applicationContext))
            .usePlugin(TaskListPlugin.create(context.applicationContext))
            .build()
    }
}

@Composable
internal fun MarkdownText(
    markdown: String,
    renderer: Markwon,
    modifier: Modifier = Modifier,
    streaming: Boolean = false,
) {
    val segments = remember(markdown, streaming) {
        splitMarkdownSegments(markdown, streaming)
    }
    val onlyText = segments.singleOrNull() as? MarkdownSegment.Text
    if (onlyText != null) {
        MarkdownBlocksText(
            markdown = onlyText.markdown,
            renderer = renderer,
            modifier = modifier,
            streaming = streaming,
        )
        return
    }

    Column(
        modifier = modifier,
    ) {
        segments.forEachIndexed { index, segment ->
            key(index, segment is MarkdownSegment.Table) {
                if (index > 0) {
                    Spacer(modifier = Modifier.height(TABLE_BLOCK_SPACING))
                }
                when (segment) {
                    is MarkdownSegment.Text -> MarkdownBlocksText(
                        markdown = segment.markdown,
                        renderer = renderer,
                        modifier = Modifier.fillMaxWidth(),
                        streaming = streaming,
                    )
                    is MarkdownSegment.Table -> NativeMarkdownTable(
                        table = segment.table,
                        renderer = renderer,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun MarkdownBlocksText(
    markdown: String,
    renderer: Markwon,
    modifier: Modifier = Modifier,
    streaming: Boolean = false,
) {
    val blocks = remember(renderer, markdown) {
        markdownNodeBlocks(renderer.parse(markdown))
    }
    if (blocks.isEmpty()) {
        MarkwonText(
            markdown = markdown,
            renderer = renderer,
            modifier = modifier,
        )
        return
    }
    if (blocks.size == 1) {
        val block = blocks.single()
        val mermaidBlock = block.mermaidBlockOrNull(streaming)
        val codeBlock = block.codeBlockOrNull()
        if (mermaidBlock != null) {
            NativeMermaidDiagram(
                source = mermaidBlock.source,
                modifier = modifier,
            )
        } else if (codeBlock != null) {
            NativeMarkdownCodeBlock(
                codeBlock = codeBlock,
                modifier = modifier,
            )
        } else {
            MarkwonNodeText(
                block = block,
                renderer = renderer,
                modifier = modifier,
            )
        }
        return
    }

    Column(modifier = modifier) {
        blocks.forEachIndexed { index, block ->
            key(index, block.node.javaClass.name) {
                if (index > 0) {
                    Spacer(modifier = Modifier.height(MARKDOWN_BLOCK_SPACING))
                }
                val mermaidBlock = block.mermaidBlockOrNull(streaming)
                val codeBlock = block.codeBlockOrNull()
                if (mermaidBlock != null) {
                    NativeMermaidDiagram(
                        source = mermaidBlock.source,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else if (codeBlock != null) {
                    NativeMarkdownCodeBlock(
                        codeBlock = codeBlock,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    MarkwonNodeText(
                        block = block,
                        renderer = renderer,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
internal fun NativeMarkdownCodeBlock(
    codeBlock: MarkdownCodeBlock,
    modifier: Modifier = Modifier,
) {
    val horizontalScrollState = rememberScrollState()
    val clipboard = LocalClipboardManager.current
    var copied by remember(codeBlock) { mutableStateOf(false) }
    var copyFeedbackTrigger by remember(codeBlock) { mutableStateOf(0) }
    val shape = RoundedCornerShape(10.dp)

    LaunchedEffect(copyFeedbackTrigger) {
        if (copyFeedbackTrigger > 0) {
            delay(2_000)
            copied = false
        }
    }

    Column(
        modifier = modifier
            .clip(shape)
            .background(CODE_BLOCK_BACKGROUND)
            .border(width = 1.dp, color = CODE_BLOCK_BORDER, shape = shape),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(CODE_TOOLBAR_HEIGHT)
                .background(CODE_TOOLBAR_BACKGROUND),
        ) {
            Text(
                text = codeBlock.language,
                color = CODE_TOOLBAR_LABEL,
                fontFamily = FontFamily.Monospace,
                fontSize = MaterialTheme.typography.labelSmall.fontSize,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 14.dp, end = 48.dp),
            )
            IconButton(
                onClick = {
                    clipboard.setText(AnnotatedString(codeBlock.code))
                    copied = true
                    copyFeedbackTrigger += 1
                },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 4.dp)
                    .size(CODE_COPY_BUTTON_SIZE),
            ) {
                Icon(
                    imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                    contentDescription = if (copied) "代码已复制" else "复制代码",
                    tint = CODE_COPY_BUTTON_CONTENT,
                    modifier = Modifier.size(15.dp),
                )
            }
        }
        HorizontalDivider(
            thickness = 1.dp,
            color = CODE_BLOCK_BORDER,
        )
        SelectionContainer {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(horizontalScrollState),
            ) {
                Text(
                    text = codeBlock.code,
                    color = CODE_BLOCK_TEXT,
                    fontFamily = FontFamily.Monospace,
                    fontSize = MaterialTheme.typography.bodySmall.fontSize,
                    lineHeight = MaterialTheme.typography.bodySmall.lineHeight,
                    softWrap = false,
                    modifier = Modifier.padding(
                        horizontal = 14.dp,
                        vertical = 12.dp,
                    ),
                )
            }
        }
    }
}

@Composable
private fun MarkwonNodeText(
    block: MarkdownNodeBlock,
    renderer: Markwon,
    modifier: Modifier = Modifier,
) {
    val renderedMarkdown = remember(renderer, block.fingerprint) {
        renderer.render(block.node)
    }
    RenderedMarkwonText(
        renderedMarkdown = renderedMarkdown,
        contentKey = block.fingerprint,
        renderer = renderer,
        modifier = modifier,
    )
}

@Composable
private fun MarkwonText(
    markdown: String,
    renderer: Markwon,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    gravity: Int = Gravity.START,
) {
    val renderedMarkdown = remember(renderer, markdown) {
        renderer.render(renderer.parse(markdown))
    }
    RenderedMarkwonText(
        renderedMarkdown = renderedMarkdown,
        contentKey = markdown,
        renderer = renderer,
        modifier = modifier,
        compact = compact,
        gravity = gravity,
    )
}

@Composable
private fun RenderedMarkwonText(
    renderedMarkdown: Spanned,
    contentKey: Any,
    renderer: Markwon,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    gravity: Int = Gravity.START,
) {
    val textColor = TextPrimary.toArgb()
    val linkColor = Primary.toArgb()
    val textStyle = if (compact) MaterialTheme.typography.bodySmall else ChatBodyTextStyle
    val textSize = textStyle.fontSize.value
    val targetLineHeight = textStyle.lineHeight.value

    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            MarkdownTextView(viewContext).apply {
                includeFontPadding = false
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                setTextIsSelectable(true)
                movementMethod = LinkMovementMethod.getInstance()
            }
        },
        update = { textView ->
            textView.setTextColor(textColor)
            textView.setLinkTextColor(linkColor)
            textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSize)
            textView.gravity = gravity or Gravity.TOP
            val desiredLineHeightPx = targetLineHeight *
                textView.resources.displayMetrics.density *
                textView.resources.configuration.fontScale
            val fontMetrics = textView.paint.fontMetricsInt
            val fontHeightPx = (fontMetrics.descent - fontMetrics.ascent).toFloat()
            textView.setLineSpacing((desiredLineHeightPx - fontHeightPx).coerceAtLeast(0f), 1f)
            // Greedy wrapping keeps completed lines in a growing table cell stationary.
            textView.breakStrategy = if (compact) {
                Layout.BREAK_STRATEGY_SIMPLE
            } else {
                Layout.BREAK_STRATEGY_HIGH_QUALITY
            }
            textView.hyphenationFrequency = if (compact) {
                Layout.HYPHENATION_FREQUENCY_NONE
            } else {
                Layout.HYPHENATION_FREQUENCY_NORMAL
            }
            if (textView.appliedRenderer !== renderer || textView.appliedContentKey != contentKey) {
                renderer.setParsedMarkdown(textView, renderedMarkdown)
                textView.appliedRenderer = renderer
                textView.appliedContentKey = contentKey
            }
        },
    )
}

@Composable
private fun NativeMarkdownTable(
    table: MarkdownTable,
    renderer: Markwon,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    val clipboard = LocalClipboardManager.current
    var copied by remember(table) { mutableStateOf(false) }
    var copyFeedbackTrigger by remember(table) { mutableStateOf(0) }
    val borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.72f)
    val headerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
    val oddRowColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.035f)
    val shape = RoundedCornerShape(8.dp)
    val clipboardText = remember(table) {
        table.toMarkdownClipboardText()
    }

    LaunchedEffect(copyFeedbackTrigger) {
        if (copyFeedbackTrigger > 0) {
            delay(2_000)
            copied = false
        }
    }

    BoxWithConstraints(modifier = modifier) {
        val columnCount = table.header.size.coerceAtLeast(1)
        val columnWidth = maxOf(MIN_TABLE_COLUMN_WIDTH, maxWidth / columnCount)
        val tableWidth = columnWidth * columnCount

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .border(width = 1.dp, color = borderColor, shape = shape),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(scrollState),
            ) {
                Column(modifier = Modifier.width(tableWidth)) {
                    NativeMarkdownTableRow(
                        cells = table.header,
                        alignments = table.alignments,
                        columnWidth = columnWidth,
                        renderer = renderer,
                        backgroundColor = headerColor,
                        borderColor = borderColor,
                        trailingContentPadding = TABLE_COPY_BUTTON_SPACE,
                    )
                    table.rows.forEachIndexed { rowIndex, cells ->
                        key(rowIndex) {
                            NativeMarkdownTableRow(
                                cells = cells,
                                alignments = table.alignments,
                                columnWidth = columnWidth,
                                renderer = renderer,
                                backgroundColor = if (rowIndex % 2 == 0) Color.Transparent else oddRowColor,
                                borderColor = borderColor,
                            )
                        }
                    }
                }
            }
            IconButton(
                onClick = {
                    clipboard.setText(AnnotatedString(clipboardText))
                    copied = true
                    copyFeedbackTrigger += 1
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(TABLE_COPY_BUTTON_SIZE),
            ) {
                Icon(
                    imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                    contentDescription = if (copied) "表格已复制" else "复制表格",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun NativeMarkdownTableRow(
    cells: List<String>,
    alignments: List<MarkdownTableAlignment>,
    columnWidth: Dp,
    renderer: Markwon,
    backgroundColor: Color,
    borderColor: Color,
    trailingContentPadding: Dp = 0.dp,
) {
    Row(
        modifier = Modifier
            .wrapContentWidth()
            .background(backgroundColor)
            .drawBehind {
                val strokeWidth = TABLE_GRID_WIDTH.toPx()
                val cellWidth = columnWidth.toPx()
                for (columnIndex in 1 until cells.size) {
                    val x = cellWidth * columnIndex
                    drawLine(
                        color = borderColor,
                        start = Offset(x, 0f),
                        end = Offset(x, size.height),
                        strokeWidth = strokeWidth,
                    )
                }
                drawLine(
                    color = borderColor,
                    start = Offset(0f, size.height),
                    end = Offset(size.width, size.height),
                    strokeWidth = strokeWidth,
                )
            },
    ) {
        cells.forEachIndexed { columnIndex, cell ->
            val alignment = alignments.getOrElse(columnIndex) { MarkdownTableAlignment.START }
            Box(
                modifier = Modifier
                    .width(columnWidth)
                    .defaultMinSize(minHeight = MIN_TABLE_ROW_HEIGHT)
                    .padding(
                        start = 10.dp,
                        end = 10.dp + if (columnIndex == cells.lastIndex) trailingContentPadding else 0.dp,
                        top = 8.dp,
                        bottom = 8.dp,
                    ),
                contentAlignment = alignment.toComposeAlignment(),
            ) {
                if (cell.isBlank()) {
                    Spacer(modifier = Modifier.height(TABLE_EMPTY_CELL_HEIGHT))
                } else {
                    MarkwonText(
                        markdown = cell,
                        renderer = renderer,
                        modifier = Modifier.fillMaxWidth(),
                        compact = true,
                        gravity = alignment.toGravity(),
                    )
                }
            }
        }
    }
}

private fun MarkdownTableAlignment.toComposeAlignment(): Alignment = when (this) {
    MarkdownTableAlignment.START -> Alignment.TopStart
    MarkdownTableAlignment.CENTER -> Alignment.TopCenter
    MarkdownTableAlignment.END -> Alignment.TopEnd
}

private fun MarkdownTableAlignment.toGravity(): Int = when (this) {
    MarkdownTableAlignment.START -> Gravity.START
    MarkdownTableAlignment.CENTER -> Gravity.CENTER_HORIZONTAL
    MarkdownTableAlignment.END -> Gravity.END
}

/** 避免父级无关重组时把相同 Markdown 再次写入 TextView 并触发布局。 */
private class MarkdownTextView(context: Context) : AppCompatTextView(context) {
    var appliedRenderer: Markwon? = null
    var appliedContentKey: Any? = null
}

private val MIN_TABLE_COLUMN_WIDTH = 112.dp
private val MIN_TABLE_ROW_HEIGHT = 40.dp
private val TABLE_EMPTY_CELL_HEIGHT = 18.dp
private val TABLE_BLOCK_SPACING = 12.dp
private val MARKDOWN_BLOCK_SPACING = 12.dp
private val TABLE_GRID_WIDTH = 0.75.dp
private val TABLE_COPY_BUTTON_SIZE = 32.dp
private val TABLE_COPY_BUTTON_SPACE = 38.dp
private val CODE_COPY_BUTTON_SIZE = 30.dp
private val CODE_TOOLBAR_HEIGHT = 36.dp
private val CODE_BLOCK_BACKGROUND = Color(0xFF202633)
private val CODE_BLOCK_BORDER = Color(0xFF2D3443)
private val CODE_BLOCK_TEXT = Color(0xFFE7EAF1)
private val CODE_TOOLBAR_BACKGROUND = Color(0xFF282F3D)
private val CODE_TOOLBAR_LABEL = Color(0xB3CBD1DC)
private val CODE_COPY_BUTTON_CONTENT = Color(0xB3CBD1DC)
