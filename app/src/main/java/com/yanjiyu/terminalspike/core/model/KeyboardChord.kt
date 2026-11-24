package com.yanjiyu.terminalspike.core.model

import com.yanjiyu.terminalspike.terminal.view.encodeTerminalChord

/**
 * One user-authored chord key. The library is device-local and shared by every keyboard deck, so a
 * chord authored once can be placed on the accessory bar and on the input keyboard.
 *
 * [spec] uses the bounded terminal chord syntax the profile already stores (`C-j`, `M-Enter`,
 * `C-[`); [label] is only the key face and is never parsed for bytes.
 */
data class KeyboardChord(
    val id: String,
    val label: String,
    val spec: String,
) {
    init {
        require(KeyboardChordLibrary.isValidId(id)) { "Keyboard chord id is not a bounded token." }
        require(label.isNotBlank() && label.length <= KeyboardChordLibrary.MAX_LABEL_LENGTH) {
            "Keyboard chord label is empty or too long."
        }
        require(encodeTerminalChord(spec) != null) {
            "Keyboard chord does not identify one supported terminal chord."
        }
    }
}

/** Bounded operations on the chord library; every entry point validates before it stores. */
object KeyboardChordLibrary {
    const val MAX_CHORDS = 10
    const val MAX_LABEL_LENGTH = 16

    private val ID_PATTERN = Regex("[a-z0-9][a-z0-9-]{0,23}")

    /** A stored row, decoded tolerantly so one unreadable entry cannot break the whole library. */
    data class Row(val id: String, val label: String, val spec: String)

    fun isValidId(id: String): Boolean = ID_PATTERN.matches(id)

    /**
     * Normalizes typed chord text into the stored spec: `c-j` and `C-J` both become `C-j`, and the
     * named keys keep the capitalization the encoder requires. Null when the result cannot emit bytes.
     */
    fun normalizeSpec(text: String): String? {
        val trimmed = text.trim().replace(" ", "")
        if (trimmed.isEmpty()) return null
        if (trimmed.length == 1) return trimmed.takeIf { encodeTerminalChord(it) != null }
        val parts = trimmed.split('-', limit = 2)
        if (parts.size != 2) return null
        val modifier = parts.first().uppercase()
        if (modifier != "C" && modifier != "M") return null
        val key = when (parts.last().lowercase()) {
            "space" -> "Space"
            "tab" -> "Tab"
            "enter" -> "Enter"
            "escape" -> "Escape"
            else -> parts.last().takeIf { it.length == 1 } ?: return null
        }
        return "$modifier-$key".takeIf { encodeTerminalChord(it) != null }
    }

    /** Deduplicated, bounded decode of stored rows; unreadable rows are dropped. */
    fun decode(rows: List<Row>): List<KeyboardChord> = rows.mapNotNull { row ->
        val spec = normalizeSpec(row.spec) ?: return@mapNotNull null
        runCatching {
            KeyboardChord(
                id = row.id,
                label = row.label.trim().ifEmpty { spec }.take(MAX_LABEL_LENGTH),
                spec = spec,
            )
        }.getOrNull()
    }.distinctBy { it.id }.take(MAX_CHORDS)

    /** Appends a chord; null when the library is full or [spec] is not one encodable chord. */
    fun add(
        chords: List<KeyboardChord>,
        label: String,
        spec: String,
        seedEpochMillis: Long,
    ): List<KeyboardChord>? {
        if (chords.size >= MAX_CHORDS) return null
        val normalized = normalizeSpec(spec) ?: return null
        val base = "c" + seedEpochMillis.toString(36)
        val taken = chords.mapTo(HashSet()) { it.id }
        var candidate = base
        var suffix = 2
        while (candidate in taken) {
            candidate = "$base-$suffix"
            suffix += 1
        }
        if (!isValidId(candidate)) return null
        return chords + KeyboardChord(
            id = candidate,
            label = label.trim().ifEmpty { normalized }.take(MAX_LABEL_LENGTH),
            spec = normalized,
        )
    }

    /** Rewrites one chord in place; null when [id] is unknown or [spec] no longer parses. */
    fun update(
        chords: List<KeyboardChord>,
        id: String,
        label: String,
        spec: String,
    ): List<KeyboardChord>? {
        val index = chords.indexOfFirst { it.id == id }
        if (index < 0) return null
        val normalized = normalizeSpec(spec) ?: return null
        val renamed = chords[index].copy(
            label = label.trim().ifEmpty { normalized }.take(MAX_LABEL_LENGTH),
            spec = normalized,
        )
        return chords.toMutableList().apply { this[index] = renamed }
    }

    fun remove(chords: List<KeyboardChord>, id: String): List<KeyboardChord> =
        chords.filterNot { it.id == id }

    /** Moves one chord inside the library; the library order is the key order on the bar. */
    fun move(chords: List<KeyboardChord>, id: String, delta: Int): List<KeyboardChord> {
        val from = chords.indexOfFirst { it.id == id }
        if (from < 0 || delta == 0) return chords
        val to = (from + delta).coerceIn(0, chords.lastIndex)
        if (to == from) return chords
        return chords.toMutableList().apply { add(to, removeAt(from)) }
    }
}
