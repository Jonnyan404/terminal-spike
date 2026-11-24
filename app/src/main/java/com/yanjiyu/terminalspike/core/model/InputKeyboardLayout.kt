package com.yanjiyu.terminalspike.core.model

import com.yanjiyu.terminalspike.terminal.view.encodeTerminalChord

/** A single flexible field surrounded by columns of at most two accessory keys. */
object InputKeyboardLayout {
    const val MAX_KEYS = 8

    /** Written only into a brand-new settings file; existing installs keep their saved layout. */
    const val NEW_INSTALL_LAYOUT = "enter,toggle,tab,shift_left,slash,ai_agents,arrow_up,arrow_down,input"

    /**
     * Prefix for a programmable key. The remainder must be one bounded terminal chord in the
     * profile syntax (`C-j`, `M-Enter`, `C-[`), so a customized key can emit a combination the
     * fixed action list does not ship.
     */
    const val CHORD_PREFIX = "chord:"

    private const val MAX_CHORD_TOKEN_LENGTH = 24

    fun defaultTokens(onRight: Boolean): List<String> =
        if (onRight) listOf("input", "enter", "toggle") else listOf("enter", "toggle", "input")

    /** Mirrors the bar around its centre: column order reverses, each column keeps top/bottom. */
    fun mirrored(tokens: List<String>): List<String> {
        val field = tokens.indexOf("input")
        fun flip(side: List<String>) = side.chunked(2).reversed().flatten()
        return flip(tokens.drop(field + 1)) + "input" + flip(tokens.take(field))
    }

    fun parse(value: String): List<String>? {
        if (value.isEmpty()) return null
        val tokens = value.split(',')
        val allowed = KeyboardAction.entries.map { it.wireCode }.toSet() + setOf("input", "toggle")
        return tokens.takeIf {
            it.size in 2..MAX_KEYS + 1 &&
                it.count { token -> token == "input" } == 1 &&
                it.any { token -> token == "toggle" } &&
                it.all { token -> token in allowed || chordSpec(token) != null }
        }
    }

    /** The validated chord behind a programmable token, or null for every other token. */
    fun chordSpec(token: String): String? {
        if (!token.startsWith(CHORD_PREFIX) || token.length > MAX_CHORD_TOKEN_LENGTH) return null
        val spec = token.removePrefix(CHORD_PREFIX)
        return spec.takeIf { encodeTerminalChord(it) != null }
    }

    /** Builds a storable token from typed text; null when the text is not a supported chord. */
    fun chordToken(text: String): String? {
        val trimmed = text.trim().replace(" ", "")
        if (trimmed.isEmpty() || trimmed.length > MAX_CHORD_TOKEN_LENGTH - CHORD_PREFIX.length) return null
        val normalized = when {
            trimmed.startsWith("c-") -> "C-" + trimmed.drop(2)
            trimmed.startsWith("m-") -> "M-" + trimmed.drop(2)
            else -> trimmed
        }
        return (CHORD_PREFIX + normalized).takeIf { chordSpec(it) != null }
    }
}
