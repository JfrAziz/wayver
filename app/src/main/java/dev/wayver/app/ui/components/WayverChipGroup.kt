package dev.wayver.app.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wayver.app.ui.AppColors

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> WayverChipGroup(
    items: List<T>,
    selected: (T) -> Boolean,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    scrollableRow: Boolean = false,
    fontSize: TextUnit = 12.sp,
    enabled: (T) -> Boolean = { true },
    leadingIcon: (@Composable (T) -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null
) {
    val chipColors =
        FilterChipDefaults.filterChipColors(
            selectedContainerColor = AppColors.primarySoft,
            selectedLabelColor = AppColors.textPrimary
        )

    val renderItem: @Composable (T) -> Unit = { item ->
        FilterChip(
            selected = selected(item),
            onClick = { onSelect(item) },
            enabled = enabled(item),
            label = { Text(label(item), fontSize = fontSize) },
            leadingIcon = leadingIcon?.let { icon -> { icon(item) } },
            colors = chipColors
        )
    }

    if (scrollableRow) {
        Row(
            modifier = modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items.forEach { renderItem(it) }
            trailingContent?.invoke()
        }
    } else {
        FlowRow(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items.forEach { renderItem(it) }
            trailingContent?.invoke()
        }
    }
}
