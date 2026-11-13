package dev.wayver.app.input

import android.view.KeyEvent

object KeyMapper {
    data class EvdevKey(val code: Int, val shift: Boolean = false)

    private val keycodeMap =
        mapOf(
            KeyEvent.KEYCODE_ESCAPE to 1,
            KeyEvent.KEYCODE_1 to 2, KeyEvent.KEYCODE_2 to 3, KeyEvent.KEYCODE_3 to 4,
            KeyEvent.KEYCODE_4 to 5, KeyEvent.KEYCODE_5 to 6, KeyEvent.KEYCODE_6 to 7,
            KeyEvent.KEYCODE_7 to 8, KeyEvent.KEYCODE_8 to 9, KeyEvent.KEYCODE_9 to 10,
            KeyEvent.KEYCODE_0 to 11, KeyEvent.KEYCODE_MINUS to 12, KeyEvent.KEYCODE_EQUALS to 13,
            KeyEvent.KEYCODE_DEL to 14, KeyEvent.KEYCODE_TAB to 15,
            KeyEvent.KEYCODE_Q to 16, KeyEvent.KEYCODE_W to 17, KeyEvent.KEYCODE_E to 18,
            KeyEvent.KEYCODE_R to 19, KeyEvent.KEYCODE_T to 20, KeyEvent.KEYCODE_Y to 21,
            KeyEvent.KEYCODE_U to 22, KeyEvent.KEYCODE_I to 23, KeyEvent.KEYCODE_O to 24,
            KeyEvent.KEYCODE_P to 25, KeyEvent.KEYCODE_LEFT_BRACKET to 26, KeyEvent.KEYCODE_RIGHT_BRACKET to 27,
            KeyEvent.KEYCODE_ENTER to 28, KeyEvent.KEYCODE_CTRL_LEFT to 29,
            KeyEvent.KEYCODE_A to 30, KeyEvent.KEYCODE_S to 31, KeyEvent.KEYCODE_D to 32,
            KeyEvent.KEYCODE_F to 33, KeyEvent.KEYCODE_G to 34, KeyEvent.KEYCODE_H to 35,
            KeyEvent.KEYCODE_J to 36, KeyEvent.KEYCODE_K to 37, KeyEvent.KEYCODE_L to 38,
            KeyEvent.KEYCODE_SEMICOLON to 39, KeyEvent.KEYCODE_APOSTROPHE to 40, KeyEvent.KEYCODE_GRAVE to 41,
            KeyEvent.KEYCODE_SHIFT_LEFT to 42, KeyEvent.KEYCODE_BACKSLASH to 43,
            KeyEvent.KEYCODE_Z to 44, KeyEvent.KEYCODE_X to 45, KeyEvent.KEYCODE_C to 46,
            KeyEvent.KEYCODE_V to 47, KeyEvent.KEYCODE_B to 48, KeyEvent.KEYCODE_N to 49,
            KeyEvent.KEYCODE_M to 50, KeyEvent.KEYCODE_COMMA to 51, KeyEvent.KEYCODE_PERIOD to 52,
            KeyEvent.KEYCODE_SLASH to 53, KeyEvent.KEYCODE_SHIFT_RIGHT to 54,
            KeyEvent.KEYCODE_ALT_LEFT to 56, KeyEvent.KEYCODE_SPACE to 57,
            KeyEvent.KEYCODE_DPAD_UP to 103, KeyEvent.KEYCODE_DPAD_LEFT to 105,
            KeyEvent.KEYCODE_DPAD_RIGHT to 106, KeyEvent.KEYCODE_DPAD_DOWN to 108
        )

    private val charMap =
        mapOf(
            '1' to EvdevKey(2), '!' to EvdevKey(2, true),
            '2' to EvdevKey(3), '@' to EvdevKey(3, true),
            '3' to EvdevKey(4), '#' to EvdevKey(4, true),
            '4' to EvdevKey(5), '$' to EvdevKey(5, true),
            '5' to EvdevKey(6), '%' to EvdevKey(6, true),
            '6' to EvdevKey(7), '^' to EvdevKey(7, true),
            '7' to EvdevKey(8), '&' to EvdevKey(8, true),
            '8' to EvdevKey(9), '*' to EvdevKey(9, true),
            '9' to EvdevKey(10), '(' to EvdevKey(10, true),
            '0' to EvdevKey(11), ')' to EvdevKey(11, true),
            '-' to EvdevKey(12), '_' to EvdevKey(12, true),
            '=' to EvdevKey(13), '+' to EvdevKey(13, true),
            '\t' to EvdevKey(15), '\n' to EvdevKey(28),
            '[' to EvdevKey(26), '{' to EvdevKey(26, true),
            ']' to EvdevKey(27), '}' to EvdevKey(27, true),
            ';' to EvdevKey(39), ':' to EvdevKey(39, true),
            '\'' to EvdevKey(40), '"' to EvdevKey(40, true),
            '`' to EvdevKey(41), '~' to EvdevKey(41, true),
            '\\' to EvdevKey(43), '|' to EvdevKey(43, true),
            ',' to EvdevKey(51), '<' to EvdevKey(51, true),
            '.' to EvdevKey(52), '>' to EvdevKey(52, true),
            '/' to EvdevKey(53), '?' to EvdevKey(53, true),
            ' ' to EvdevKey(57),
            'q' to EvdevKey(16), 'Q' to EvdevKey(16, true),
            'w' to EvdevKey(17), 'W' to EvdevKey(17, true),
            'e' to EvdevKey(18), 'E' to EvdevKey(18, true),
            'r' to EvdevKey(19), 'R' to EvdevKey(19, true),
            't' to EvdevKey(20), 'T' to EvdevKey(20, true),
            'y' to EvdevKey(21), 'Y' to EvdevKey(21, true),
            'u' to EvdevKey(22), 'U' to EvdevKey(22, true),
            'i' to EvdevKey(23), 'I' to EvdevKey(23, true),
            'o' to EvdevKey(24), 'O' to EvdevKey(24, true),
            'p' to EvdevKey(25), 'P' to EvdevKey(25, true),
            'a' to EvdevKey(30), 'A' to EvdevKey(30, true),
            's' to EvdevKey(31), 'S' to EvdevKey(31, true),
            'd' to EvdevKey(32), 'D' to EvdevKey(32, true),
            'f' to EvdevKey(33), 'F' to EvdevKey(33, true),
            'g' to EvdevKey(34), 'G' to EvdevKey(34, true),
            'h' to EvdevKey(35), 'H' to EvdevKey(35, true),
            'j' to EvdevKey(36), 'J' to EvdevKey(36, true),
            'k' to EvdevKey(37), 'K' to EvdevKey(37, true),
            'l' to EvdevKey(38), 'L' to EvdevKey(38, true),
            'z' to EvdevKey(44), 'Z' to EvdevKey(44, true),
            'x' to EvdevKey(45), 'X' to EvdevKey(45, true),
            'c' to EvdevKey(46), 'C' to EvdevKey(46, true),
            'v' to EvdevKey(47), 'V' to EvdevKey(47, true),
            'b' to EvdevKey(48), 'B' to EvdevKey(48, true),
            'n' to EvdevKey(49), 'N' to EvdevKey(49, true),
            'm' to EvdevKey(50), 'M' to EvdevKey(50, true)
        )

    fun toEvdev(keyCode: Int): Int? = keycodeMap[keyCode]

    fun fromChar(ch: Char): EvdevKey? = charMap[ch]

    fun modifiers(event: KeyEvent): Int {
        var m = 0
        if (event.isShiftPressed) m = m or 1
        if (event.isCtrlPressed) m = m or 4
        if (event.isAltPressed) m = m or 8
        return m
    }
}
