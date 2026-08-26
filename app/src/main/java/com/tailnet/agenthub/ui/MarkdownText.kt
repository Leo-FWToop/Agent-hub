package com.tailnet.agenthub.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 轻量 Markdown 渲染（无第三方依赖）。
 *
 * 支持：标题、粗体/斜体/行内代码/链接、围栏代码块（含流式未闭合）、
 * 表格、无序/有序列表、引用块。
 *
 * 解析失败时降级为纯段落显示，任何输入都不会导致崩溃。
 */

private sealed class MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock()
    data class Paragraph(val text: String) : MdBlock()
    data class Code(val lang: String, val code: String) : MdBlock()
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock()
    data class ListItem(val ordered: Boolean, val index: Int, val text: String) : MdBlock()
    data class Quote(val text: String) : MdBlock()
}

// 所有正则提升为顶层常量：避免每次解析重复编译
private val INLINE_TOKEN = Regex(
    "`([^`\\n]+)`" +
        "|\\*\\*([^*\\n]+?)\\*\\*" +
        "|(?<!\\*)\\*([^*\\n]+?)\\*(?!\\*)" +
        "|\\[([^\\]\\n]+)\\]\\(([^)\\s]+)\\)"
)
private val TABLE_SEPARATOR = Regex("\\|?[\\s:|-]+\\|?")
private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
private val UL_ITEM = Regex("^[-*+]\\s+(.*)$")
private val OL_ITEM = Regex("^(\\d+)[.、]\\s*(.*)$")
private val QUOTE = Regex("^>\\s?(.*)$")
private val TABLE_CELL = Regex("(?<!\\\\)\\|")

/** 块级解析：按行扫描划分代码块/表格/标题/列表/引用/段落；任何异常降级为段落 */
private fun parseBlocks(raw: String): List<MdBlock> {
    return try {
        parseBlocksImpl(raw)
    } catch (_: Throwable) {
        // 解析器不应崩溃：降级为纯段落
        listOf(MdBlock.Paragraph(raw))
    }
}

private fun parseBlocksImpl(raw: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val lines = raw.lines()
    var i = 0
    val para = StringBuilder()

    fun flushPara() {
        val text = para.toString().trim()
        if (text.isNotEmpty()) blocks += MdBlock.Paragraph(text)
        para.setLength(0)
    }

    while (i < lines.size) {
        val line = lines[i]

        // 围栏代码块（允许未闭合：流式期间剩余内容全部按代码渲染）
        if (line.trimStart().startsWith("```")) {
            flushPara()
            val lang = line.trim().removePrefix("```").trim()
            val code = StringBuilder()
            i++
            var closed = false
            while (i < lines.size) {
                if (lines[i].trimStart().startsWith("```")) {
                    closed = true
                    i++
                    break
                }
                code.appendLine(lines[i])
                i++
            }
            blocks += MdBlock.Code(
                lang,
                if (closed) code.toString() else code.toString().trimEnd()
            )
            continue
        }

        // 表格：当前行以 | 开头，下一行是 |---| 形式的分隔行
        if (line.trimStart().startsWith("|") && i + 1 < lines.size &&
            TABLE_SEPARATOR.matches(lines[i + 1].trim())
        ) {
            flushPara()
            val header = splitTableRow(line)
            i += 2
            val rows = mutableListOf<List<String>>()
            while (i < lines.size && lines[i].trimStart().startsWith("|")) {
                rows += splitTableRow(lines[i])
                i++
            }
            blocks += MdBlock.Table(header, rows)
            continue
        }

        val heading = HEADING.find(line)
        if (heading != null) {
            flushPara()
            blocks += MdBlock.Heading(
                heading.groupValues[1].length,
                heading.groupValues[2].trim()
            )
            i++
            continue
        }

        val ulItem = UL_ITEM.find(line)
        if (ulItem != null) {
            flushPara()
            blocks += MdBlock.ListItem(false, 0, ulItem.groupValues[1])
            i++
            continue
        }

        val olItem = OL_ITEM.find(line)
        if (olItem != null) {
            flushPara()
            blocks += MdBlock.ListItem(
                true,
                olItem.groupValues[1].toIntOrNull() ?: 1,
                olItem.groupValues[2]
            )
            i++
            continue
        }

        val quote = QUOTE.find(line)
        if (quote != null) {
            flushPara()
            blocks += MdBlock.Quote(quote.groupValues[1])
            i++
            continue
        }

        if (line.isBlank()) {
            flushPara()
        } else {
            if (para.isNotEmpty()) para.append('\n')
            para.append(line.trim())
        }
        i++
    }
    flushPara()
    return blocks
}

/** 拆表格行：| a | b | → [a, b]；容忍首尾竖线与单元格内的转义竖线 */
private fun splitTableRow(line: String): List<String> {
    var s = line.trim()
    if (s.startsWith("|")) s = s.substring(1)
    if (s.isNotEmpty() && s.endsWith("|")) s = s.substring(0, s.length - 1)
    return s.split(TABLE_CELL).map { it.trim().replace("\\|", "|") }
}

@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    // 解析结果按内容缓存；解析内部已做异常降级
    val blocks = remember(text) { parseBlocks(text) }
    val typography = MaterialTheme.typography

    Column(modifier = modifier) {
        blocks.forEachIndexed { idx, block ->
            when (block) {
                is MdBlock.Heading -> {
                    if (idx > 0) Spacer(Modifier.height(6.dp))
                    Text(
                        inlineStyled(block.text),
                        style = when (block.level.coerceIn(1, 6)) {
                            1 -> typography.titleLarge
                            2 -> typography.titleMedium
                            else -> typography.titleSmall
                        },
                        fontWeight = if (block.level >= 3) FontWeight.SemiBold else FontWeight.Bold,
                    )
                    Spacer(Modifier.height(4.dp))
                }

                is MdBlock.Paragraph -> {
                    if (idx > 0) Spacer(Modifier.height(6.dp))
                    Text(inlineStyled(block.text), style = typography.bodyMedium)
                }

                is MdBlock.Code -> CodeBlock(block.lang, block.code)

                is MdBlock.Table -> {
                    if (idx > 0) Spacer(Modifier.height(6.dp))
                    TableBlock(block)
                }

                is MdBlock.ListItem -> {
                    Row(Modifier.padding(start = 4.dp, bottom = 2.dp)) {
                        Text(
                            if (block.ordered) "${block.index}. " else "•  ",
                            style = typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(inlineStyled(block.text), style = typography.bodyMedium)
                    }
                }

                is MdBlock.Quote -> {
                    if (idx > 0) Spacer(Modifier.height(4.dp))
                    Row {
                        Spacer(
                            Modifier
                                .width(3.dp)
                                .height(18.dp)
                                .background(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                                    RoundedCornerShape(2.dp)
                                )
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            inlineStyled(block.text),
                            style = typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 行内语法（code/粗体/斜体/链接）转 AnnotatedString。
 * 不使用 remember：该函数在多个块类型中调用，跨调用点共享
 * 组合槽位缓存在块结构变化时可能错位；直接构建开销可忽略。
 */
@Composable
private fun inlineStyled(text: String): AnnotatedString {
    val codeColor = MaterialTheme.colorScheme.onSurfaceVariant
    val codeBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
    val linkColor = MaterialTheme.colorScheme.primary
    return buildAnnotatedString {
        var pos = 0
        while (pos < text.length) {
            val m = INLINE_TOKEN.find(text, pos) ?: break
            if (m.range.first > pos) append(text.substring(pos, m.range.first))
            val g = m.groupValues
            when {
                g[1].isNotEmpty() -> { // 行内代码
                    append(g[1])
                    addStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            background = codeBg,
                            color = codeColor,
                        ),
                        length - g[1].length, length
                    )
                }

                g[2].isNotEmpty() -> { // 粗体
                    append(g[2])
                    addStyle(
                        SpanStyle(fontWeight = FontWeight.Bold),
                        length - g[2].length, length
                    )
                }

                g[3].isNotEmpty() -> { // 斜体
                    append(g[3])
                    addStyle(
                        SpanStyle(fontStyle = FontStyle.Italic),
                        length - g[3].length, length
                    )
                }

                else -> { // 链接：显示文本，带链接样式
                    val label = g[4]
                    append(label)
                    addStyle(
                        SpanStyle(
                            color = linkColor,
                            textDecoration = TextDecoration.Underline,
                        ),
                        length - label.length, length
                    )
                }
            }
            pos = m.range.last + 1
        }
        if (pos < text.length) append(text.substring(pos))
    }
}

/** 围栏代码块：深色底 + 等宽字体 + 横向滚动（长行不折行） */
@Composable
private fun CodeBlock(lang: String, code: String) {
    val dark = isSystemInDarkTheme()
    val bg = if (dark) Color(0xFF1C1F26) else Color(0xFF272B33)
    val fg = if (dark) Color(0xFFD7DCE4) else Color(0xFFE6EAF0)
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = bg,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Column(Modifier.padding(10.dp)) {
            if (lang.isNotBlank()) {
                Text(
                    lang,
                    style = MaterialTheme.typography.labelSmall,
                    color = fg.copy(alpha = 0.55f),
                )
                Spacer(Modifier.height(4.dp))
            }
            Column(Modifier.horizontalScroll(rememberScrollState())) {
                Text(
                    code,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = fg,
                )
            }
        }
    }
}

/** 表格：首行表头加粗，逐行渲染，单元格自动换行 */
@Composable
private fun TableBlock(table: MdBlock.Table) {
    val colCount = maxOf(table.header.size, table.rows.maxOfOrNull { it.size } ?: 0)
    if (colCount == 0) return

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(8.dp)) {
            Row(Modifier.fillMaxWidth()) {
                repeat(colCount) { c ->
                    Text(
                        inlineStyled(table.header.getOrElse(c) { "" }),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 4.dp),
                    )
                }
            }
            Spacer(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            )
            table.rows.forEach { row ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                ) {
                    repeat(colCount) { c ->
                        Text(
                            inlineStyled(row.getOrElse(c) { "" }),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 4.dp),
                        )
                    }
                }
            }
        }
    }
}
