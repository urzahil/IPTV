package rocks.urzahil.iptv.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.stringPreferencesKey
import rocks.urzahil.iptv.core.model.StreamHeaders
import rocks.urzahil.iptv.player.model.BufferProfile
import rocks.urzahil.iptv.player.model.DeinterlaceMode
import rocks.urzahil.iptv.player.model.EnginePreference
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class SettingsRepository(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(
        PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            produceFile = { File(context.applicationContext.filesDir, "datastore/settings.preferences_pb") },
        ),
    )

    val settings: Flow<AppSettings> = store.data.map { p ->
        val d = AppSettings()
        AppSettings(
            epgFutureDays = (p[FUTURE] ?: d.epgFutureDays).coerceIn(AppSettings.MIN_FUTURE_DAYS, AppSettings.MAX_FUTURE_DAYS),
            epgPastDays = (p[PAST] ?: d.epgPastDays).coerceIn(AppSettings.MIN_PAST_DAYS, AppSettings.MAX_PAST_DAYS),
            playlistRefreshHours = (p[PLAYLIST_HOURS] ?: d.playlistRefreshHours).coerceAtLeast(0),
            epgRefreshHours = (p[EPG_HOURS] ?: d.epgRefreshHours).coerceAtLeast(0),
            defaultUserAgent = p[USER_AGENT]?.let(StreamHeaders::sanitizeValue)?.takeIf { it.isNotEmpty() } ?: d.defaultUserAgent,
            sortByNumber = p[SORT_BY_NUMBER] ?: d.sortByNumber,
            showRadio = p[SHOW_RADIO] ?: d.showRadio,
            engine = EnginePreference.parse(p[ENGINE]),
            bufferProfile = BufferProfile.parse(p[BUFFER]),
            retryAttempts = (p[RETRIES] ?: d.retryAttempts).coerceIn(0, AppSettings.MAX_RETRIES),
            audioPassthrough = p[PASSTHROUGH] ?: d.audioPassthrough,
            deinterlace = DeinterlaceMode.parse(p[DEINTERLACE]),
            backgroundAudio = p[BACKGROUND_AUDIO] ?: d.backgroundAudio,
            preferredAudioLanguage = p[AUDIO_LANG].orEmpty(),
            preferredTextLanguage = p[TEXT_LANG].orEmpty(),
            invertSwipeZap = p[INVERT_SWIPE] ?: d.invertSwipeZap,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setEpgFutureDays(v: Int) = store.edit { it[FUTURE] = v.coerceIn(AppSettings.MIN_FUTURE_DAYS, AppSettings.MAX_FUTURE_DAYS) }
    suspend fun setEpgPastDays(v: Int) = store.edit { it[PAST] = v.coerceIn(AppSettings.MIN_PAST_DAYS, AppSettings.MAX_PAST_DAYS) }
    suspend fun setPlaylistRefreshHours(v: Int) = store.edit { it[PLAYLIST_HOURS] = v }
    suspend fun setEpgRefreshHours(v: Int) = store.edit { it[EPG_HOURS] = v }
    suspend fun setDefaultUserAgent(v: String) = store.edit { it[USER_AGENT] = StreamHeaders.sanitizeValue(v) }
    suspend fun setSortByNumber(v: Boolean) = store.edit { it[SORT_BY_NUMBER] = v }
    suspend fun setShowRadio(v: Boolean) = store.edit { it[SHOW_RADIO] = v }

    suspend fun setEngine(v: EnginePreference) = store.edit { it[ENGINE] = v.name }
    suspend fun setBufferProfile(v: BufferProfile) = store.edit { it[BUFFER] = v.name }
    suspend fun setRetryAttempts(v: Int) = store.edit { it[RETRIES] = v.coerceIn(0, AppSettings.MAX_RETRIES) }
    suspend fun setAudioPassthrough(v: Boolean) = store.edit { it[PASSTHROUGH] = v }
    suspend fun setDeinterlace(v: DeinterlaceMode) = store.edit { it[DEINTERLACE] = v.name }
    suspend fun setBackgroundAudio(v: Boolean) = store.edit { it[BACKGROUND_AUDIO] = v }
    suspend fun setPreferredAudioLanguage(v: String) = store.edit { it[AUDIO_LANG] = v.trim().lowercase() }
    suspend fun setPreferredTextLanguage(v: String) = store.edit { it[TEXT_LANG] = v.trim().lowercase() }
    suspend fun setInvertSwipeZap(v: Boolean) = store.edit { it[INVERT_SWIPE] = v }

    private companion object {
        val ENGINE = stringPreferencesKey("player_engine")
        val BUFFER = stringPreferencesKey("player_buffer")
        val RETRIES = intPreferencesKey("player_retries")
        val PASSTHROUGH = booleanPreferencesKey("player_passthrough")
        val DEINTERLACE = stringPreferencesKey("player_deinterlace")
        val BACKGROUND_AUDIO = booleanPreferencesKey("player_background_audio")
        val AUDIO_LANG = stringPreferencesKey("player_audio_lang")
        val TEXT_LANG = stringPreferencesKey("player_text_lang")
        val INVERT_SWIPE = booleanPreferencesKey("player_invert_swipe")
        val FUTURE = intPreferencesKey("epg_future_days")
        val PAST = intPreferencesKey("epg_past_days")
        val PLAYLIST_HOURS = intPreferencesKey("playlist_refresh_hours")
        val EPG_HOURS = intPreferencesKey("epg_refresh_hours")
        val USER_AGENT = stringPreferencesKey("default_user_agent")
        val SORT_BY_NUMBER = booleanPreferencesKey("sort_by_number")
        val SHOW_RADIO = booleanPreferencesKey("show_radio")
    }
}
