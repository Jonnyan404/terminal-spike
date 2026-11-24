package com.yanjiyu.terminalspike.ui.settings

import com.yanjiyu.terminalspike.core.model.KeyboardAction
import com.yanjiyu.terminalspike.core.model.KeyboardDefaultInheritance
import com.yanjiyu.terminalspike.core.model.KeyboardLayout
import com.yanjiyu.terminalspike.core.model.KeyboardProfile
import com.yanjiyu.terminalspike.core.model.ModifierBehavior
import com.yanjiyu.terminalspike.core.model.TerminalInputMode
import com.yanjiyu.terminalspike.terminal.view.encodeTerminalChord

/**
 * Device-local preset decks behind the keyboard preset chips.
 *
 * A shipped preset is a template until the user edits it; from then on its deck is stored, so
 * switching away no longer discards the changes and the preset can still be reset to the shipped
 * keys. Custom presets are extra entries with their own label, created and maintained by the user.
 *
 * Payloads only carry deck fields, so restoring one keeps the profile identity, creation time, and
 * connection bindings. They are bounded and treated as untrusted: anything unreadable falls back to
 * the shipped (or live) deck instead of failing the write.
 */
internal object KeyboardPresetStore {
    const val MAX_CUSTOM_PRESETS = 12
    const val MAX_LABEL_LENGTH = 40

    private const val CUSTOM_PREFIX = "custom:"
    private const val VERSION = "v1"
    private const val FIELD_COUNT = 8
    private const val MAX_PAYLOAD_LENGTH = 2048
    private const val SEPARATOR = '|'

    /** Stored deck of one preset; [label] is only set for custom presets. */
    data class Deck(val payload: String, val label: String = "")

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
    fun restore(payload: String, profile: KeyboardProfile): KeyboardProfile? {
        if (payload.length > MAX_PAYLOAD_LENGTH) return null
        val parts = payload.split(SEPARATOR, limit = FIELD_COUNT)
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

    fun isCustom(presetId: String): Boolean = KeyboardPresets.shipped(presetId) == null

    fun newCustomId(existing: Set<String>, seedEpochMillis: Long): String {
        val base = CUSTOM_PREFIX + seedEpochMillis.toString(36)
        var candidate = base
        var suffix = 2
        while (candidate in existing) {
            candidate = "$base-$suffix"
            suffix += 1
        }
        return candidate
    }

    /**
     * Preset the live deck belongs to. The stored choice wins, so an edited preset still reads as
     * that preset; without one, the deck is matched against the shipped keys.
     */
    fun selectedId(stored: String?, profile: KeyboardProfile, decks: Map<String, Deck>): String {
        val known = stored?.takeIf { it.isNotEmpty() && (KeyboardPresets.shipped(it) != null || it in decks) }
        return known
            ?: KeyboardPresets.selectable.firstOrNull { it.matches(profile) }?.id
            ?: KeyboardPresets.general.id
    }

    /** Deck a preset stands for: its stored edits, else its shipped keys, else the live deck. */
    fun deckFor(presetId: String, decks: Map<String, Deck>, current: KeyboardProfile): KeyboardProfile {
        decks[presetId]?.let { entry -> restore(entry.payload, current)?.let { return it } }
        val shipped = KeyboardPresets.shipped(presetId) ?: return current
        return shippedDeck(shipped, current)
    }

    /**
     * Stores [deck] as the deck of [presetId]. A shipped preset whose deck is back to its shipped
     * keys drops its entry, so "edited" stays distinguishable from "untouched".
     */
    fun save(presetId: String, decks: Map<String, Deck>, deck: KeyboardProfile): Map<String, Deck> {
        val next = LinkedHashMap(decks)
        val shipped = KeyboardPresets.shipped(presetId)
        if (shipped != null && shipped.matches(deck)) {
            next.remove(presetId)
        } else {
            next[presetId] = Deck(payload = snapshot(deck), label = decks[presetId]?.label.orEmpty())
        }
        return next
    }

    /** The keys a preset ships with, carrying over the parts a preset does not describe. */
    fun shippedDeck(preset: KeyboardPreset, current: KeyboardProfile): KeyboardProfile = current.copy(
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

    fun labelOf(raw: String, fallback: String): String =
        raw.trim().take(MAX_LABEL_LENGTH).ifEmpty { fallback }
}
