package com.arloor.tuke.feature.agent

import com.arloor.tuke.core.agent.AgentEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentModelUiStateTest {
    @Test
    fun `selector stays hidden until model settings are initialized`() {
        val state = AgentUiState(modelSettingsLoading = true)

        assertFalse(state.showModelDropdown)
        assertFalse(state.modelSelectorEnabled)
    }

    @Test
    fun `background refresh keeps dropdown visible but temporarily disables it`() {
        val state = AgentUiState(
            modelSettingsInitialized = true,
            modelSettingsLoading = true,
        )

        assertTrue(state.showModelDropdown)
        assertFalse(state.modelSelectorEnabled)
    }

    @Test
    fun `existing conversation never shows model dropdown`() {
        val state = AgentUiState(
            events = listOf(AgentEvent()),
            modelSettingsInitialized = true,
        )

        assertFalse(state.showModelDropdown)
        assertFalse(state.modelSelectorEnabled)
    }
}
