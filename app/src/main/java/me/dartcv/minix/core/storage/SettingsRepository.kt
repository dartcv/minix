package me.dartcv.minix.core.storage

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import me.dartcv.minix.core.model.AccentOption
import me.dartcv.minix.core.model.AppSettings
import me.dartcv.minix.core.model.DefaultMapPoints
import me.dartcv.minix.core.model.MapPoint
import me.dartcv.minix.core.model.Preset
import me.dartcv.minix.core.model.ResponseMode
import me.dartcv.minix.core.model.ThemeMode
import me.dartcv.minix.core.serialization.MapPointListCodec
import me.dartcv.minix.core.serialization.PresetListCodec

private val Context.minixDataStore by preferencesDataStore(name = "minix_settings")

class SettingsRepository(context: Context) {
    private val dataStore = context.applicationContext.minixDataStore

    private val preferences: Flow<Preferences> = dataStore.data
        .catch { error ->
            if (error is IOException) {
                emit(emptyPreferences())
            } else {
                throw error
            }
        }

    val settings: Flow<AppSettings> = preferences
        .map { preferences ->
            AppSettings(
                gridEnabled = preferences[Keys.GRID_ENABLED] ?: true,
                hapticsEnabled = preferences[Keys.HAPTICS_ENABLED] ?: true,
                panelOpacity = ((preferences[Keys.PANEL_OPACITY] ?: 88) / 100f).coerceIn(0.55f, 1f),
                panelScale = ((preferences[Keys.PANEL_SCALE] ?: 100) / 100f).coerceIn(0.8f, 1.2f),
                overlayX = preferences[Keys.OVERLAY_X] ?: 24,
                overlayY = preferences[Keys.OVERLAY_Y] ?: 180,
                overlayExpanded = preferences[Keys.OVERLAY_EXPANDED] ?: false,
                selectedPreset = preferences[Keys.SELECTED_PRESET] ?: "平衡",
                sensitivity = ((preferences[Keys.SENSITIVITY] ?: 72) / 100f).coerceIn(0.2f, 1f),
                responseMode = enumValueOrDefault(
                    preferences[Keys.RESPONSE_MODE],
                    ResponseMode.BALANCED,
                ),
                alignmentEnabled = preferences[Keys.ALIGNMENT_ENABLED] ?: true,
                motionPreviewEnabled = preferences[Keys.MOTION_PREVIEW_ENABLED] ?: false,
                effectHighlightEnabled = preferences[Keys.EFFECT_HIGHLIGHT_ENABLED] ?: true,
                themeMode = enumValueOrDefault(
                    preferences[Keys.THEME_MODE],
                    ThemeMode.SYSTEM,
                ),
                accentOption = enumValueOrDefault(
                    preferences[Keys.ACCENT_OPTION],
                    AccentOption.INK,
                ),
                backgroundUri = preferences[Keys.BACKGROUND_URI],
                audioUri = preferences[Keys.AUDIO_URI],
                favoriteEntryIds = preferences[Keys.FAVORITE_ENTRY_IDS].orEmpty(),
            )
        }

    val customPresets: Flow<List<Preset>> = preferences.map { preferences ->
        preferences[Keys.CUSTOM_PRESETS]
            ?.let { raw -> runCatching { PresetListCodec.decode(raw) }.getOrNull() }
            .orEmpty()
    }

    val mapPoints: Flow<List<MapPoint>> = preferences.map { preferences ->
        preferences[Keys.MAP_POINTS]
            ?.let { raw -> runCatching { MapPointListCodec.decode(raw) }.getOrNull() }
            ?: DefaultMapPoints.all
    }

    suspend fun setGridEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.GRID_ENABLED] = enabled }
    }

    suspend fun setHapticsEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.HAPTICS_ENABLED] = enabled }
    }

    suspend fun setPanelOpacity(value: Float) {
        dataStore.edit { it[Keys.PANEL_OPACITY] = (value.coerceIn(0.55f, 1f) * 100).toInt() }
    }

    suspend fun setPanelScale(value: Float) {
        dataStore.edit { it[Keys.PANEL_SCALE] = (value.coerceIn(0.8f, 1.2f) * 100).toInt() }
    }

    suspend fun setSensitivity(value: Float) {
        dataStore.edit { it[Keys.SENSITIVITY] = (value.coerceIn(0.2f, 1f) * 100).toInt() }
    }

    suspend fun setResponseMode(value: ResponseMode) {
        dataStore.edit { it[Keys.RESPONSE_MODE] = value.name }
    }

    suspend fun setAlignmentEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.ALIGNMENT_ENABLED] = enabled }
    }

    suspend fun setMotionPreviewEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.MOTION_PREVIEW_ENABLED] = enabled }
    }

    suspend fun setEffectHighlightEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.EFFECT_HIGHLIGHT_ENABLED] = enabled }
    }

    suspend fun setThemeMode(value: ThemeMode) {
        dataStore.edit { it[Keys.THEME_MODE] = value.name }
    }

    suspend fun setAccentOption(value: AccentOption) {
        dataStore.edit { it[Keys.ACCENT_OPTION] = value.name }
    }

    suspend fun setBackgroundUri(uri: String?) {
        dataStore.edit {
            if (uri == null) it.remove(Keys.BACKGROUND_URI) else it[Keys.BACKGROUND_URI] = uri
        }
    }

    suspend fun setAudioUri(uri: String?) {
        dataStore.edit {
            if (uri == null) it.remove(Keys.AUDIO_URI) else it[Keys.AUDIO_URI] = uri
        }
    }

    suspend fun toggleFavoriteEntry(id: String) {
        dataStore.edit { preferences ->
            val favorites = preferences[Keys.FAVORITE_ENTRY_IDS].orEmpty().toMutableSet()
            if (!favorites.add(id)) favorites.remove(id)
            preferences[Keys.FAVORITE_ENTRY_IDS] = favorites
        }
    }

    suspend fun updateOverlayPosition(x: Int, y: Int) {
        dataStore.edit {
            it[Keys.OVERLAY_X] = x
            it[Keys.OVERLAY_Y] = y
        }
    }

    suspend fun setOverlayExpanded(expanded: Boolean) {
        dataStore.edit { it[Keys.OVERLAY_EXPANDED] = expanded }
    }

    suspend fun selectPreset(name: String) {
        dataStore.edit { it[Keys.SELECTED_PRESET] = name }
    }

    suspend fun applyPreset(preset: Preset) {
        dataStore.edit {
            it[Keys.SELECTED_PRESET] = preset.name
            it[Keys.GRID_ENABLED] = preset.gridEnabled
            it[Keys.PANEL_OPACITY] = (preset.panelOpacity.coerceIn(0.55f, 1f) * 100).toInt()
            it[Keys.PANEL_SCALE] = (preset.panelScale.coerceIn(0.8f, 1.2f) * 100).toInt()
            it[Keys.SENSITIVITY] = (preset.sensitivity.coerceIn(0.2f, 1f) * 100).toInt()
            it[Keys.RESPONSE_MODE] = preset.responseMode.name
            it[Keys.ACCENT_OPTION] = preset.accentOption.name
        }
    }

    suspend fun setCustomPresets(presets: List<Preset>) {
        val custom = presets.filterNot(Preset::isBuiltIn)
        require(custom.size <= 97) { "Too many custom presets" }
        val json = PresetListCodec.encode(custom)
        dataStore.edit { it[Keys.CUSTOM_PRESETS] = json }
    }

    suspend fun setMapPoints(points: List<MapPoint>) {
        require(points.size <= 500) { "Too many map points" }
        val json = MapPointListCodec.encode(points)
        dataStore.edit { it[Keys.MAP_POINTS] = json }
    }

    suspend fun importLocalData(
        settings: AppSettings,
        presets: List<Preset>,
        mapPoints: List<MapPoint>,
    ) {
        val customPresetJson = PresetListCodec.encode(presets.filterNot(Preset::isBuiltIn))
        val mapPointJson = MapPointListCodec.encode(mapPoints)
        dataStore.edit {
            it[Keys.GRID_ENABLED] = settings.gridEnabled
            it[Keys.HAPTICS_ENABLED] = settings.hapticsEnabled
            it[Keys.PANEL_OPACITY] = (settings.panelOpacity.coerceIn(0.55f, 1f) * 100).toInt()
            it[Keys.PANEL_SCALE] = (settings.panelScale.coerceIn(0.8f, 1.2f) * 100).toInt()
            it[Keys.OVERLAY_X] = settings.overlayX
            it[Keys.OVERLAY_Y] = settings.overlayY
            it[Keys.OVERLAY_EXPANDED] = settings.overlayExpanded
            it[Keys.SELECTED_PRESET] = settings.selectedPreset
            it[Keys.SENSITIVITY] = (settings.sensitivity.coerceIn(0.2f, 1f) * 100).toInt()
            it[Keys.RESPONSE_MODE] = settings.responseMode.name
            it[Keys.ALIGNMENT_ENABLED] = settings.alignmentEnabled
            it[Keys.MOTION_PREVIEW_ENABLED] = settings.motionPreviewEnabled
            it[Keys.EFFECT_HIGHLIGHT_ENABLED] = settings.effectHighlightEnabled
            it[Keys.THEME_MODE] = settings.themeMode.name
            it[Keys.ACCENT_OPTION] = settings.accentOption.name
            if (settings.backgroundUri == null) {
                it.remove(Keys.BACKGROUND_URI)
            } else {
                it[Keys.BACKGROUND_URI] = settings.backgroundUri
            }
            if (settings.audioUri == null) {
                it.remove(Keys.AUDIO_URI)
            } else {
                it[Keys.AUDIO_URI] = settings.audioUri
            }
            it[Keys.FAVORITE_ENTRY_IDS] = settings.favoriteEntryIds
            it[Keys.CUSTOM_PRESETS] = customPresetJson
            it[Keys.MAP_POINTS] = mapPointJson
        }
    }

    suspend fun reset() {
        dataStore.edit { it.clear() }
    }

    private object Keys {
        val GRID_ENABLED = booleanPreferencesKey("grid_enabled")
        val HAPTICS_ENABLED = booleanPreferencesKey("haptics_enabled")
        val PANEL_OPACITY = intPreferencesKey("panel_opacity")
        val PANEL_SCALE = intPreferencesKey("panel_scale")
        val OVERLAY_X = intPreferencesKey("overlay_x")
        val OVERLAY_Y = intPreferencesKey("overlay_y")
        val OVERLAY_EXPANDED = booleanPreferencesKey("overlay_expanded")
        val SELECTED_PRESET = stringPreferencesKey("selected_preset")
        val SENSITIVITY = intPreferencesKey("sensitivity")
        val RESPONSE_MODE = stringPreferencesKey("response_mode")
        val ALIGNMENT_ENABLED = booleanPreferencesKey("alignment_enabled")
        val MOTION_PREVIEW_ENABLED = booleanPreferencesKey("motion_preview_enabled")
        val EFFECT_HIGHLIGHT_ENABLED = booleanPreferencesKey("effect_highlight_enabled")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val ACCENT_OPTION = stringPreferencesKey("accent_option")
        val BACKGROUND_URI = stringPreferencesKey("background_uri")
        val AUDIO_URI = stringPreferencesKey("audio_uri")
        val CUSTOM_PRESETS = stringPreferencesKey("custom_presets")
        val MAP_POINTS = stringPreferencesKey("map_points")
        val FAVORITE_ENTRY_IDS = stringSetPreferencesKey("favorite_entry_ids")
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String?, fallback: T): T {
        return value?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback
    }
}
