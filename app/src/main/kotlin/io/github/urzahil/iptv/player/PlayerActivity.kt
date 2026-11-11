package io.github.urzahil.iptv.player

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.urzahil.iptv.ui.theme.IptvTheme

/**
 * Placeholder for the player milestone. The intent carries ids only (never URLs or credentials):
 * [EXTRA_CHANNEL_ID] and optionally [EXTRA_CATEGORY_ID] (the category the user launched from, used for zapping).
 */
class PlayerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val channelId = intent.getLongExtra(EXTRA_CHANNEL_ID, -1)
        setContent {
            IptvTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("Player not implemented yet", style = MaterialTheme.typography.titleLarge)
                        Text("Channel #$channelId", style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = { finish() }) { Text("Back") }
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_CHANNEL_ID = "channelId"
        const val EXTRA_CATEGORY_ID = "categoryId"

        fun intent(context: Context, channelId: Long, categoryId: Long?): Intent =
            Intent(context, PlayerActivity::class.java).putExtra(EXTRA_CHANNEL_ID, channelId).apply {
                if (categoryId != null) putExtra(EXTRA_CATEGORY_ID, categoryId)
            }
    }
}
