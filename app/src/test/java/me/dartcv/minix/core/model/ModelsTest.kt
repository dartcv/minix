package me.dartcv.minix.core.model

import me.dartcv.minix.root.RootReadOnlyFieldProfileStatus
import me.dartcv.minix.root.RootReadOnlyFieldsState
import me.dartcv.minix.root.RootRuntimeState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsTest {
    @Test
    fun appSettingsDefaultsRepresentTheLocalPreviewConfiguration() {
        val settings = AppSettings()

        assertTrue(settings.gridEnabled)
        assertTrue(settings.hapticsEnabled)
        assertEquals(0.88f, settings.panelOpacity, 0f)
        assertEquals(1f, settings.panelScale, 0f)
        assertEquals(24, settings.overlayX)
        assertEquals(180, settings.overlayY)
        assertFalse(settings.overlayExpanded)
        assertEquals("平衡", settings.selectedPreset)
        assertEquals(0.72f, settings.sensitivity, 0f)
        assertEquals(ResponseMode.BALANCED, settings.responseMode)
        assertTrue(settings.alignmentEnabled)
        assertFalse(settings.motionPreviewEnabled)
        assertTrue(settings.effectHighlightEnabled)
        assertEquals(ThemeMode.SYSTEM, settings.themeMode)
        assertEquals(AccentOption.INK, settings.accentOption)
        assertEquals(null, settings.backgroundUri)
        assertEquals(null, settings.audioUri)
        assertTrue(settings.favoriteEntryIds.isEmpty())
    }

    @Test
    fun mainUiStateStartsOnHomeWithNoActiveOverlay() {
        val state = MainUiState()

        assertEquals(AppDestination.HOME, state.destination)
        assertEquals(OverlaySessionState.STOPPED, state.overlayState)
        assertFalse(state.permissions.canDrawOverlays)
        assertTrue(state.permissions.notificationGranted)
        assertFalse(state.permissions.notificationRequired)
        assertEquals(AppSettings(), state.settings)
    }

    @Test
    fun destinationsExposeTheExpectedStableNavigationOrder() {
        assertEquals(
            listOf(
                AppDestination.HOME,
                AppDestination.CONTROLS,
                AppDestination.PRESETS,
                AppDestination.LIBRARY,
                AppDestination.SETTINGS,
            ),
            AppDestination.entries.toList(),
        )
    }

    @Test
    fun mainUiStateProjectsTypedReadOnlyFieldsFromRootState() {
        val fields = RootReadOnlyFieldsState(
            profileStatus = RootReadOnlyFieldProfileStatus.READY,
            profileSummary = "ready",
        )
        val state = MainUiState(
            rootState = RootRuntimeState(readOnlyFields = fields),
        )

        assertEquals(fields, state.readOnlyFields)
    }
}
