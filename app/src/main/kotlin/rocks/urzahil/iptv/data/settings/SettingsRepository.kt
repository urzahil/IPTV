package rocks.urzahil.iptv.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.stringPreferencesKey
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
            defaultUserAgent = p[USER_AGENT]?.takeIf { it.isNotBlank() } ?: d.defaultUserAgent,
            sortByNumber = p[SORT_BY_NUMBER] ?: d.sortByNumber,
            showRadio = p[SHOW_RADIO] ?: d.showRadio,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setEpgFutureDays(v: Int) = store.edit { it[FUTURE] = v.coerceIn(AppSettings.MIN_FUTURE_DAYS, AppSettings.MAX_FUTURE_DAYS) }
    suspend fun setEpgPastDays(v: Int) = store.edit { it[PAST] = v.coerceIn(AppSettings.MIN_PAST_DAYS, AppSettings.MAX_PAST_DAYS) }
    suspend fun setPlaylistRefreshHours(v: Int) = store.edit { it[PLAYLIST_HOURS] = v }
    suspend fun setEpgRefreshHours(v: Int) = store.edit { it[EPG_HOURS] = v }
    suspend fun setDefaultUserAgent(v: String) = store.edit { it[USER_AGENT] = v.trim() }
    suspend fun setSortByNumber(v: Boolean) = store.edit { it[SORT_BY_NUMBER] = v }
    suspend fun setShowRadio(v: Boolean) = store.edit { it[SHOW_RADIO] = v }

    private companion object {
        val FUTURE = intPreferencesKey("epg_future_days")
        val PAST = intPreferencesKey("epg_past_days")
        val PLAYLIST_HOURS = intPreferencesKey("playlist_refresh_hours")
        val EPG_HOURS = intPreferencesKey("epg_refresh_hours")
        val USER_AGENT = stringPreferencesKey("default_user_agent")
        val SORT_BY_NUMBER = booleanPreferencesKey("sort_by_number")
        val SHOW_RADIO = booleanPreferencesKey("show_radio")
    }
}
