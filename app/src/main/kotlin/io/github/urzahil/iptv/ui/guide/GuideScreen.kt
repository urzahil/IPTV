package io.github.urzahil.iptv.ui.guide

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.urzahil.iptv.ui.components.EmptyState
import io.github.urzahil.iptv.ui.components.TabScaffold

/** Placeholder for the EPG guide milestone. Data access: EpgRepository.programmesBatch / programmes. */
@Composable
fun GuideScreen(onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    TabScaffold(title = { Text("Guide") }, onOpenSettings = onOpenSettings, modifier = modifier) { padding ->
        EmptyState(
            title = "TV guide",
            message = "The programme grid is coming in a later milestone.",
            modifier = Modifier.padding(padding),
        )
    }
}
