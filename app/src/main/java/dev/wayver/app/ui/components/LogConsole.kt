package dev.wayver.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Immutable
data class LogEntry(
    val id: Long,
    val text: String
)

@Immutable
data class LogBuffer(
    val lines: List<LogEntry> = emptyList(),
    val version: Long = 0L
)

@Composable
fun LogConsole(
    buffer: LogBuffer,
    textColor: Color,
    modifier: Modifier = Modifier,
    emptyText: String? = null,
    fontSize: TextUnit = 10.sp,
    lineHeight: TextUnit = 14.sp,
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        if (buffer.lines.isNotEmpty()) {
            listState.scrollToItem(buffer.lines.lastIndex)
        }
    }
    LaunchedEffect(buffer.version) {
        if (buffer.lines.isNotEmpty() && !listState.canScrollForward) {
            listState.scrollToItem(buffer.lines.lastIndex)
        }
    }

    if (buffer.lines.isEmpty() && emptyText != null) {
        Text(
            text = emptyText,
            color = textColor,
            fontSize = fontSize,
            fontFamily = FontFamily.Monospace,
            modifier = modifier
        )
    } else {
        LazyColumn(
            state = listState,
            modifier = modifier,
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            items(items = buffer.lines, key = { it.id }) { entry ->
                Text(
                    text = entry.text,
                    color = textColor,
                    fontSize = fontSize,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = lineHeight
                )
            }
        }
    }
}
