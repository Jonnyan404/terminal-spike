package com.yanjiyu.terminalspike.ui.settings

import com.yanjiyu.terminalspike.core.model.KeyboardAction
import com.yanjiyu.terminalspike.core.model.KeyboardDefaultInheritance
import com.yanjiyu.terminalspike.core.model.KeyboardLayout
import com.yanjiyu.terminalspike.core.model.KeyboardProfile
import com.yanjiyu.terminalspike.core.model.ModifierBehavior
import com.yanjiyu.terminalspike.core.model.TerminalInputMode
import com.yanjiyu.terminalspike.terminal.view.encodeTerminalChord

/**
 * Remembers one customized deck per preset so switching presets stops discarding user edits.
 *
 * A snapshot only carries the deck fields. Restoring copies them onto the profile being edited, so
 * the profile identity, creation time, and connection bindings survive. Payloads are device-local,
 * bounded, and treated as untrusted: anything unreadable falls back to the shipped preset.
 */
internal object KeyboardPresetMemory {
    /** Reserved key naming the preset the live profile was last applied from. */
    const val BASE_KEY = "@base"

    /** Reported by [KeyboardPresets.selectedId] when no shipped preset matches the live deck. */
    const val CUSTOM_PRESET_ID = "custom"

    private const val VERSION = "v1"
    private const val FIELD_COUNT = 8
    private const val MAX_VALUE_LENGTH = 2048
    private const val SEPARATOR = '|'

    data class Applied(val memory: Map<String, String>, val profile: KeyboardProfile)

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

    /** Bounded copy: only the selectable preset keys and the reserved base key survive. */
    fun sanitized(memory: Map<String, String>): Map<String, String> = buildMap {
        KeyboardPresets.selectable.forEach { preset -> memory[preset.id]?.let { put(preset.id, it) } }
        memory[BASE_KEY]?.let { put(BASE_KEY, it) }
    }

    /** The preset the live deck was last applied from, defaulting to the shipped General preset. */
    fun baseId(memory: Map<String, String>, profile: KeyboardProfile): String {
        val stored = sanitized(memory)[BASE_KEY]
        if (stored != null && KeyboardPresets.selectable.any { it.id == stored }) return stored
        return KeyboardPresets.selectedId(profile)
            .takeIf { it != CUSTOM_PRESET_ID }
            ?: KeyboardPresets.general.id
    }

    /**
     * Saves the live deck under its base preset when it no longer matches that preset, then returns
     * the deck to persist: the remembered snapshot for [preset] or the shipped preset keys.
     * [forget] is the explicit "restore General preset" path and deliberately keeps no snapshot.
     */
    fun apply(
        memory: Map<String, String>,
        current: KeyboardProfile,
        preset: KeyboardPreset,
        nowEpochMillis: Long,
        forget: Boolean = false,
    ): Applied {
        val next = sanitized(memory).toMutableMap()
        if (forget) {
            next.remove(preset.id)
        } else {
            val basePreset = KeyboardPresets.selectable.firstOrNull { it.id == baseId(memory, current) }
            if (basePreset != null && !basePreset.matches(current)) {
                next[basePreset.id] = snapshot(current)
            }
        }
        val restored = next[preset.id]?.let { restore(it, current) }
        val deck = restored ?: current.copy(
            orderedActions = preset.actions,
            layout = preset.layout,
            modifierBehavior = preset.modifierBehavior,
            hapticFeedbackEnabled = preset.hapticFeedbackEnabled,
            keyRepeatEnabled = preset.keyRepeatEnabled,
            inputMode = preset.inputMode,
            tmuxPrefix = preset.tmuxPrefix,
            defaultLayoutState = if (preset.id == KeyboardPresets.general.id) {
                KeyboardDefaultInheritance.reset(current).defaultLayoutState
            } else {
                null
            },
        )
        next[BASE_KEY] = preset.id
        return Applied(
            memory = next,
            profile = deck.copy(updatedAtEpochMillis = maxOf(nowEpochMillis, deck.updatedAtEpochMillis)),
        )
    }
}
