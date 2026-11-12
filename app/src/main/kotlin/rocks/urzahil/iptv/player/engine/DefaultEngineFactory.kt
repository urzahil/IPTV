package rocks.urzahil.iptv.player.engine

import android.content.Context
import okhttp3.OkHttpClient
import rocks.urzahil.iptv.player.model.EngineKind

class DefaultEngineFactory(private val context: Context, private val okHttp: OkHttpClient) : EngineFactory {
    override fun create(kind: EngineKind, config: EngineConfig): PlayerEngine = when (kind) {
        EngineKind.MEDIA3 -> Media3Engine(context, okHttp, config)
        EngineKind.VLC -> VlcEngine(context, config)
    }
}
