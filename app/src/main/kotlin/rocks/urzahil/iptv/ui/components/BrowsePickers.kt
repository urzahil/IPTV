package rocks.urzahil.iptv.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import rocks.urzahil.iptv.data.db.entities.CategoryEntity
import rocks.urzahil.iptv.data.db.entities.SourceEntity
import rocks.urzahil.iptv.ui.theme.Dimens

// Source and category pickers shared by the Channels and Guide tabs.

@Composable
fun SourceEmptyState(source: SourceEntity, onRetry: () -> Unit) {
    when (source.status) {
        SourceEntity.STATUS_ERROR, SourceEntity.STATUS_NEEDS_CREDENTIALS -> EmptyState(
            title = "Could not load channels",
            message = source.lastError ?: "Unknown error",
            actionLabel = "Try again",
            onAction = onRetry,
            isError = true,
        )
        SourceEntity.STATUS_NEW -> EmptyState(
            title = "Importing channels",
            message = "This can take a minute for large playlists.",
        )
        else -> EmptyState(title = "No channels", message = "This source has no live channels in this category.")
    }
}

@Composable
fun SourceTitle(sources: List<SourceEntity>, current: SourceEntity?, onSelect: (Long) -> Unit, fallback: String = "Channels") {
    if (sources.size <= 1 || current == null) {
        Text(current?.name ?: fallback, maxLines = 1, overflow = TextOverflow.Ellipsis)
        return
    }
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.heightIn(min = Dimens.MinTouchTarget).clickable { open = true },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(current.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Icon(Icons.Filled.ArrowDropDown, contentDescription = "Choose source")
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            sources.forEach { s ->
                DropdownMenuItem(text = { Text(s.name) }, onClick = { open = false; onSelect(s.id) })
            }
        }
    }
}

@Composable
fun CategoryChips(categories: List<CategoryEntity>, selected: Long?, onSelect: (Long?) -> Unit) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = Dimens.ScreenPadding, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "all") {
            FilterChip(selected = selected == null, onClick = { onSelect(null) }, label = { Text("All") })
        }
        items(categories, key = { it.id }) { c ->
            FilterChip(selected = selected == c.id, onClick = { onSelect(c.id) }, label = { Text(c.name, maxLines = 1) })
        }
    }
}
