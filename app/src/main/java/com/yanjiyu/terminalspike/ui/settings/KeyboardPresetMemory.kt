package com.yanjiyu.terminalspike.ui.settings

import com.yanjiyu.terminalspike.core.model.KeyboardAction
import com.yanjiyu.terminalspike.core.model.KeyboardDefaultInheritance
import com.yanjiyu.terminalspike.core.model.KeyboardLayout
import com.yanjiyu.terminalspike.core.model.KeyboardProfile
import com.yanjiyu.terminalspike.core.model.ModifierBehavior
import com.yanjiyu.terminalspike.core.model.TerminalInputMode
import com.yanjiyu.terminalspike.terminal.view.encodeTerminalChord

/**
 * Keeps the deck the user built out of a preset alive as its own Custom configuration.
 *
 * Presets are read-only templates; editing the deck never modifies one, it produces a custom deck.
 * That deck used to live only in the live profile, so picking any preset overwrote it and the
 * customization was gone with no way back. Now every edit also stores a copy, and the Custom preset
 * chip restores it.
 *
 * A snapshot only carries the deck fields. Restoring copies them onto the profile being edited, so
 * the profile identity, creation time, and connection bindings survive. Payloads are device-local,
 * bounded, and treated as untrusted: anything unreadable falls back to the live deck.
 */
internal object KeyboardPresetMemory {
    private const val VERSION = "v1"
    private const val FIELD_COUNT = 8
    private const val MAX_VALUE_LENGTH = 2048
    private const val SEPARATOR = '|'

    data class Applied(val memory: String, val profile: KeyboardProfile)

    fun snapshot(profile: KeyboardProfile): String = listOf(
        VERSION,
        profile.orderedActions.joinToString(",") { it.wireCode },
        profile.layout.name,
        profile.modifierBehavior.name,
        if (profile.hapticFeedbackEnabled) "1" else "0",
        if (profile.keyRepeatEnabled) "1" else "0",
        profile.inputMode.name,
        profile.tmuxPrefix,
    ).joinToString(SEPARATOR.toString())

    /** Null when the payload is foreign, truncated, or no longer describes a usable deck. */
    fun restore(value: String, profile: KeyboardProfile): KeyboardProfile? {
        if (value.length > MAX_VALUE_LENGTH) return null
        val parts = value.split(SEPARATOR, limit = FIELD_COUNT)
        if (parts.size != FIELD_COUNT || parts[0] != VERSION) return null
        val codes = parts[1].split(',')
        val actions = codes.mapNotNull { code -> KeyboardAction.entries.firstOrNull { it.wireCode == code } }
        if (actions.size != codes.size || actions.isEmpty()) return null
        if (actions.distinct().size != actions.size) return null
        val layout = KeyboardLayout.entries.firstOrNull { it.name == parts[2] } ?: return null
        val modifierBehavior = ModifierBehavior.entries.firstOrNull { it.name == parts[3] } ?: return null
        val inputMode = TerminalInputMode.entries.firstOrNull { it.name == parts[6] } ?: return null
        if (encodeTerminalChord(parts[7]) == null) return null
        return runCatching {
            profile.copy(
                orderedActions = actions,
                layout = layout,
                modifierBehavior = modifierBehavior,
                hapticFeedbackEnabled = parts[4] == "1",
                keyRepeatEnabled = parts[5] == "1",
                inputMode = inputMode,
                tmuxPrefix = parts[7],
                defaultLayoutState = null,
            )
        }.getOrNull()
    }

    /** The saved custom deck, or null when the user never built one or the payload is unusable. */
    fun saved(memory: String, profile: KeyboardProfile): KeyboardProfile? =
        memory.takeIf { it.isNotEmpty() }?.let { restore(it, profile) }

    fun hasSavedDeck(memory: String): Boolean = memory.isNotEmpty()

    /**
     * Payload to store after [updated] replaced [previous]. Only a deck that matches no shipped
     * preset is remembered; applying a preset leaves the saved custom deck untouched.
     */
    fun remember(memory: String, updated: KeyboardProfile): String =
        if (KeyboardPresets.selectedId(updated) == KeyboardPresets.CUSTOM_ID) snapshot(updated) else memory

    /**
     * Applies [target], or the saved custom deck when [target] is null, and returns the deck to
     * persist plus the memory it implies. Leaving a custom deck for a preset stores it first, so a
     * customization can never be dropped by a switch.
     */
    fun apply(
        memory: String,
        current: KeyboardProfile,
        target: KeyboardPreset?,
        nowEpochMillis: Long,
    ): Applied {
        val deck = if (target == null) {
            saved(memory, current) ?: current
        } else {
            current.copy(
                orderedActions = target.actions,
                layout = target.layout,
                modifierBehavior = target.modifierBehavior,
                hapticFeedbackEnabled = target.hapticFeedbackEnabled,
                keyRepeatEnabled = target.keyRepeatEnabled,
                inputMode = target.inputMode,
                tmuxPrefix = target.tmuxPrefix,
                defaultLayoutState = if (target.id == KeyboardPresets.general.id) {
                    KeyboardDefaultInheritance.reset(current).defaultLayoutState
                } else {
                    null
                },
            )
        }
        return Applied(
            memory = remember(memory, current),
            profile = deck.copy(updatedAtEpochMillis = maxOf(nowEpochMillis, deck.updatedAtEpochMillis)),
        )
    }
}
