package org.marxreader.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.marxreader.app.data.TocNode
import org.marxreader.app.data.TocNodeType

private data class TreeRow(
    val node: TocNode,
    val depth: Int,
    val expandable: Boolean,
    val expanded: Boolean
)

/** A real, collapsible tree. parentId controls ownership; level is only presentation metadata. */
@Composable
fun TocTree(
    nodes: List<TocNode>,
    currentChapterId: String?,
    modifier: Modifier = Modifier,
    currentParagraph: Int = 0,
    chapterProgress: Map<String, Int> = emptyMap(),
    headerContent: (@Composable () -> Unit)? = null,
    autoScrollToCurrent: Boolean = false,
    onSelect: (chapterId: String, paragraphIndex: Int) -> Unit
) {
    val byParent = remember(nodes) {
        nodes.groupBy { it.parentId }.mapValues { (_, value) -> value.sortedBy(TocNode::order) }
    }
    val nodeById = remember(nodes) { nodes.associateBy(TocNode::id) }
    val currentSection = remember(nodes, currentChapterId, currentParagraph) {
        nodes.asSequence().filter { it.type == TocNodeType.SECTION }
            .filter { it.chapterId == currentChapterId && it.paragraphIndex <= currentParagraph }
            .maxByOrNull(TocNode::paragraphIndex)
    }
    val currentNode = currentSection ?: nodes.firstOrNull {
        it.chapterId == currentChapterId && it.type in setOf(TocNodeType.CHAPTER, TocNodeType.PREFACE, TocNodeType.APPENDIX)
    }
    val currentPathIds = remember(currentNode, nodeById) {
        buildSet {
            var node = currentNode
            while (node != null) {
                add(node.id)
                node = node.parentId?.let(nodeById::get)
            }
        }
    }
    val currentPathTitle = remember(currentNode, nodeById) {
        buildList {
            var node = currentNode
            while (node != null) {
                if (node.type != TocNodeType.VOLUME) add(node.title)
                node = node.parentId?.let(nodeById::get)
            }
        }.asReversed().joinToString(" › ")
    }
    var collapsed by remember(nodes) { mutableStateOf(emptySet<String>()) }
    var descending by remember { mutableStateOf(false) }
    LaunchedEffect(currentPathIds) { collapsed = collapsed - currentPathIds }

    val rows = remember(nodes, byParent, collapsed, descending) {
        buildList {
            fun visit(parentId: String?, depth: Int) {
                val children = byParent[parentId].orEmpty().let { if (descending) it.asReversed() else it }
                children.forEach { node ->
                    val grandchildren = byParent[node.id].orEmpty()
                    val isRoot = node.parentId == null && node.type == TocNodeType.VOLUME
                    if (!isRoot) add(TreeRow(node, depth, grandchildren.isNotEmpty(), node.id !in collapsed))
                    if (node.id !in collapsed) visit(node.id, if (isRoot) depth else depth + 1)
                }
            }
            visit(null, 0)
        }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(autoScrollToCurrent, rows, currentNode?.id) {
        if (autoScrollToCurrent) {
            val index = rows.indexOfFirst { it.node.id == currentNode?.id }
            if (index >= 0) listState.scrollToItem(index + 1)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 28.dp)
    ) {
        if (headerContent != null) item("directory-header") {
            Box(Modifier.padding(horizontal = 18.dp)) { headerContent() }
        }
        item("directory-controls") {
            Column {
                Row(
                    Modifier.fillMaxWidth().padding(start = 15.dp, end = 7.dp, top = 8.dp, bottom = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "目录 · ${nodes.count { it.type in setOf(TocNodeType.CHAPTER, TocNodeType.PREFACE, TocNodeType.APPENDIX) }} 章 · ${nodes.count { it.type == TocNodeType.SECTION }} 小节",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (currentPathTitle.isNotBlank()) Text(
                            currentPathTitle,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = {
                        collapsed = if (collapsed.isEmpty()) {
                            nodes.filter { byParent[it.id].orEmpty().isNotEmpty() && it.id !in currentPathIds }
                                .mapTo(mutableSetOf(), TocNode::id)
                        } else emptySet()
                    }) { Text(if (collapsed.isEmpty()) "收起" else "展开") }
                    TextButton(onClick = { descending = !descending }) { Text(if (descending) "倒序" else "正序") }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .65f))
            }
        }
        items(rows, key = { it.node.id }) { row ->
            TreeLine(
                row = row,
                selected = row.node.id == currentNode?.id ||
                    (row.node.type != TocNodeType.SECTION && row.node.chapterId == currentChapterId),
                hasRead = row.node.chapterId?.let { chapterProgress.containsKey(it) } == true,
                onExpand = {
                    collapsed = if (row.node.id in collapsed) collapsed - row.node.id else collapsed + row.node.id
                },
                onClick = {
                    row.node.chapterId?.let { onSelect(it, row.node.paragraphIndex) }
                        ?: if (row.expandable) {
                            collapsed = if (row.node.id in collapsed) collapsed - row.node.id else collapsed + row.node.id
                        } else Unit
                }
            )
        }
    }
}

@Composable
private fun TreeLine(
    row: TreeRow,
    selected: Boolean,
    hasRead: Boolean,
    onExpand: () -> Unit,
    onClick: () -> Unit
) {
    val node = row.node
    val isGroup = node.type == TocNodeType.PART
    val isSection = node.type == TocNodeType.SECTION
    val rowHeight = if (isGroup) 46.dp else 50.dp
    Column(
        Modifier.fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 2.dp)
            .background(
                when {
                    selected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = .72f)
                    isGroup -> MaterialTheme.colorScheme.surfaceContainer
                    else -> Color.Transparent
                },
                shape = MaterialTheme.shapes.small
            )
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
    ) {
        Row(Modifier.fillMaxWidth().height(rowHeight), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.width(3.dp).height(if (selected) 26.dp else 0.dp)
                    .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, MaterialTheme.shapes.extraSmall)
            )
            Spacer(Modifier.width((10 + row.depth * 18).dp))
            if (row.expandable) {
                Icon(
                    if (row.expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = if (row.expanded) "收起子目录" else "展开子目录",
                    modifier = Modifier.size(32.dp).clickable(onClick = onExpand).padding(6.dp),
                    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Box(Modifier.width(32.dp), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier.size(if (hasRead) 6.dp else 4.dp)
                            .background(
                                if (hasRead) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outlineVariant,
                                shape = MaterialTheme.shapes.extraSmall
                            )
                    )
                }
            }
            Text(
                node.title,
                modifier = Modifier.weight(1f).padding(end = 14.dp),
                color = when {
                    selected -> MaterialTheme.colorScheme.onPrimaryContainer
                    isSection -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.onSurface
                },
                fontSize = when { isGroup -> 14.sp; isSection -> 14.sp; else -> 15.sp },
                fontWeight = if (selected || isGroup) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 2,
                lineHeight = 18.sp,
                overflow = TextOverflow.Ellipsis
            )
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = (45 + row.depth * 18).dp, end = 15.dp),
            color = if (selected) Color.Transparent else MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isGroup) .48f else .3f)
        )
    }
}
