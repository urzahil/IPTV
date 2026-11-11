package rocks.urzahil.iptv.data.repo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import rocks.urzahil.iptv.data.db.dao.ChannelPrefDao
import rocks.urzahil.iptv.data.db.entities.ChannelPrefEntity
import rocks.urzahil.iptv.player.model.AspectMode
import rocks.urzahil.iptv.player.model.EngineKind
import rocks.urzahil.iptv.player.model.EnginePreference

/** Per-channel player choices: forced engine, remembered automatic fallback, track languages, aspect mode. */
class ChannelPrefsRepository(private val dao: ChannelPrefDao) {
    private val lock = Mutex()

    suspend fun get(channelId: Long): ChannelPrefEntity? = withContext(Dispatchers.IO) { dao.get(channelId) }

    private suspend fun edit(channelId: Long, f: (ChannelPrefEntity) -> ChannelPrefEntity) = withContext(Dispatchers.IO) {
        lock.withLock { dao.upsert(f(dao.get(channelId) ?: ChannelPrefEntity(channelId))) }
    }

    /** [EnginePreference.AUTO] clears the override and the remembered automatic fallback. */
    suspend fun setEngine(channelId: Long, pref: EnginePreference) = edit(channelId) {
        if (pref == EnginePreference.AUTO) it.copy(engine = null, autoEngine = null, autoEngineSetMs = null)
        else it.copy(engine = pref.name)
    }

    suspend fun rememberAutoEngine(channelId: Long, kind: EngineKind, nowMs: Long) = edit(channelId) {
        it.copy(autoEngine = kind.name, autoEngineSetMs = nowMs)
    }

    suspend fun setAudioLang(channelId: Long, lang: String?) = edit(channelId) { it.copy(audioLang = lang) }
    suspend fun setTextLang(channelId: Long, lang: String?) = edit(channelId) { it.copy(textLang = lang) }
    suspend fun setAspect(channelId: Long, mode: AspectMode) = edit(channelId) { it.copy(aspectMode = mode.name) }
}
