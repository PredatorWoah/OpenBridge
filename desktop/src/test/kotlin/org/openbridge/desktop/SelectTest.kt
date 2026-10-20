package org.openbridge.desktop

import androidx.compose.ui.state.ToggleableState
import kotlin.test.Test
import kotlin.test.assertEquals

class SelectTest {
    @Test
    fun theFolderCheckboxSaysAllNoneOrSome() {
        val shown = listOf("a", "b", "c")
        assertEquals(ToggleableState.Off, selectAllState(emptySet(), shown))
        assertEquals(ToggleableState.Indeterminate, selectAllState(setOf("b"), shown))
        assertEquals(ToggleableState.On, selectAllState(setOf("a", "b", "c"), shown))
        // Items hidden by the filter don't count.
        assertEquals(ToggleableState.On, selectAllState(setOf("a", "b", "c", "hidden"), shown))
        assertEquals(ToggleableState.Off, selectAllState(setOf("hidden"), shown))
        assertEquals(ToggleableState.Off, selectAllState(emptySet(), emptyList()))
    }

    @Test
    fun aCheckboxTogglesOneItemAndKeepsTheRest() {
        val state = AppState().apply {
            entries = listOf(Entry("1", "a.jpg", false, 1), Entry("2", "b.jpg", false, 1), Entry("3", "c.jpg", false, 1))
        }
        state.click(state.entries[0], ctrl = false, shift = false)
        state.toggle(state.entries[2])
        assertEquals(setOf("1", "3"), state.selection)
        state.toggle(state.entries[0])
        assertEquals(setOf("3"), state.selection)
        // Shift-click still stretches from the last item touched (here by its checkbox).
        state.click(state.entries[1], ctrl = false, shift = true)
        assertEquals(setOf("1", "2"), state.selection)
        state.selectAll()
        assertEquals(setOf("1", "2", "3"), state.selection)
        state.selectNone()
        assertEquals(emptySet(), state.selection)
    }
}
