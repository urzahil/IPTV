package rocks.urzahil.iptv.ui.components

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import rocks.urzahil.iptv.data.db.dao.ChannelListItem
import rocks.urzahil.iptv.data.repo.NowNext
import rocks.urzahil.iptv.ui.theme.Dimens

/** Scaffold for the bottom-navigation destinations: the outer scaffold already handles the bottom inset. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabScaffold(
    title: @Composable () -> Unit,
    onOpenSettings: (() -> Unit)?,
    modifier: Modifier = Modifier,
    floatingActionButton: @Composable () -> Unit = {},
    content: @Composable (androidx.compose.foundation.layout.PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier,
        floatingActionButton = floatingActionButton,
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = title,
                actions = {
                    if (onOpenSettings != null) {
                        IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        content = content,
    )
}

@Composable
fun LoadingView(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
fun EmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    isError: Boolean = false,
) {
    Column(
        modifier.fillMaxSize().padding(Dimens.ScreenPadding * 2),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (actionLabel != null && onAction != null) Button(onClick = onAction) { Text(actionLabel) }
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.fillMaxWidth().padding(horizontal = Dimens.ScreenPadding, vertical = 12.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

/** One channel line: logo, name and number, now/next with progress, favourite star. Long-press toggles favourite. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChannelRow(
    item: ChannelListItem,
    nowNext: NowNext?,
    nowMs: Long,
    onClick: () -> Unit,
    onToggleFavourite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ListItem(
        modifier = modifier.combinedClickable(onClick = onClick, onLongClick = onToggleFavourite),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { ChannelLogo(item) },
        headlineContent = {
            Text(
                if (item.number != null) "${item.number}  ${item.name}" else item.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            val now = nowNext?.now
            if (now != null) {
                Column {
                    Text(now.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val span = (now.endMs - now.startMs).coerceAtLeast(1)
                    LinearProgressIndicator(
                        progress = { ((nowMs - now.startMs).toFloat() / span).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    )
                    nowNext.next?.let {
                        Text(
                            "Next: ${it.title}",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        trailingContent = {
            IconButton(onClick = onToggleFavourite) {
                if (item.fav) {
                    Icon(Icons.Filled.Star, contentDescription = "Remove from favourites", tint = MaterialTheme.colorScheme.primary)
                } else {
                    Icon(Icons.Outlined.StarBorder, contentDescription = "Add to favourites")
                }
            }
        },
    )
}

@Composable
private fun ChannelLogo(item: ChannelListItem) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier.size(Dimens.ChannelLogo).clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        if (item.logoUrl.isNullOrBlank()) {
            Text(
                item.name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            AsyncImage(
                model = item.logoUrl,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(4.dp),
            )
        }
    }
}

fun formatAgo(ms: Long, nowMs: Long = System.currentTimeMillis()): String =
    if (ms <= 0) "never" else DateUtils.getRelativeTimeSpanString(ms, nowMs, DateUtils.MINUTE_IN_MILLIS).toString()

/** Current time, refreshed every 30 s (drives programme progress bars). */
@Composable
fun rememberNowMs(): Long {
    val state = androidx.compose.runtime.produceState(System.currentTimeMillis()) {
        while (true) {
            kotlinx.coroutines.delay(30_000)
            value = System.currentTimeMillis()
        }
    }
    return state.value
}

/** Scaffold for pushed screens (add/edit/settings) with a back arrow. Handles all system bar insets itself. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (androidx.compose.foundation.layout.PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        content = content,
    )
}
