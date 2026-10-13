package com.makay.remote.core

import android.view.KeyEvent

/**
 * Android tus olaylarini Windows `InputRelay.cs` anahtar adina cevirir.
 *
 * InputRelay sirasiyla: ad sozlugu -> tek karakter (KEYEVENTF_UNICODE) ->
 * F1..F24 -> numpad0..9. Asagidaki adlar o sozlukte birebir bulunur.
 */
object VirtualKeys {

    /** Android tus kodu -> InputRelay anahtar adi. */
    private val BY_KEYCODE: Map<Int, String> = buildMap {
        put(KeyEvent.KEYCODE_ENTER, "enter")
        put(KeyEvent.KEYCODE_NUMPAD_ENTER, "enter")
        put(KeyEvent.KEYCODE_ESCAPE, "escape")
        put(KeyEvent.KEYCODE_DEL, "backspace")       // Android'de DEL = backspace
        put(KeyEvent.KEYCODE_FORWARD_DEL, "delete")  // Android'de FORWARD_DEL = delete
        put(KeyEvent.KEYCODE_TAB, "tab")
        put(KeyEvent.KEYCODE_SPACE, "space")
        put(KeyEvent.KEYCODE_INSERT, "insert")
        put(KeyEvent.KEYCODE_DPAD_UP, "arrowup")
        put(KeyEvent.KEYCODE_DPAD_DOWN, "arrowdown")
        put(KeyEvent.KEYCODE_DPAD_LEFT, "arrowleft")
        put(KeyEvent.KEYCODE_DPAD_RIGHT, "arrowright")
        put(KeyEvent.KEYCODE_MOVE_HOME, "home")
        put(KeyEvent.KEYCODE_MOVE_END, "end")
        put(KeyEvent.KEYCODE_PAGE_UP, "pageup")
        put(KeyEvent.KEYCODE_PAGE_DOWN, "pagedown")
        put(KeyEvent.KEYCODE_SHIFT_LEFT, "shift")
        put(KeyEvent.KEYCODE_SHIFT_RIGHT, "shift")
        put(KeyEvent.KEYCODE_CTRL_LEFT, "control")
        put(KeyEvent.KEYCODE_CTRL_RIGHT, "control")
        put(KeyEvent.KEYCODE_ALT_LEFT, "alt")
        put(KeyEvent.KEYCODE_ALT_RIGHT, "alt")
        put(KeyEvent.KEYCODE_META_LEFT, "win")
        put(KeyEvent.KEYCODE_META_RIGHT, "win")
        put(KeyEvent.KEYCODE_CAPS_LOCK, "capslock")
        put(KeyEvent.KEYCODE_NUM_LOCK, "numlock")
        put(KeyEvent.KEYCODE_SCROLL_LOCK, "scrolllock")
        put(KeyEvent.KEYCODE_SYSRQ, "printscreen")
        put(KeyEvent.KEYCODE_BREAK, "pause")
        put(KeyEvent.KEYCODE_MENU, "contextmenu")
        put(KeyEvent.KEYCODE_CLEAR, "clear")
        put(KeyEvent.KEYCODE_GRAVE, "`")
        put(KeyEvent.KEYCODE_MINUS, "-")
        put(KeyEvent.KEYCODE_EQUALS, "=")
        put(KeyEvent.KEYCODE_LEFT_BRACKET, "[")
        put(KeyEvent.KEYCODE_RIGHT_BRACKET, "]")
        put(KeyEvent.KEYCODE_BACKSLASH, "\\")
        put(KeyEvent.KEYCODE_SEMICOLON, ";")
        put(KeyEvent.KEYCODE_APOSTROPHE, "'")
        put(KeyEvent.KEYCODE_COMMA, ",")
        put(KeyEvent.KEYCODE_PERIOD, ".")
        put(KeyEvent.KEYCODE_SLASH, "/")
    }

    /**
     * @return InputRelay'in anlayacagi anahtar adi, yoksa null.
     */
    fun nameFor(keyCode: Int): String? {
        BY_KEYCODE[keyCode]?.let { return it }

        // F1..F24  ->  "f1".."f24"
        if (keyCode in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12) {
            return "f" + (keyCode - KeyEvent.KEYCODE_F1 + 1)
        }
        if (keyCode >= KeyEvent.KEYCODE_F13 && keyCode <= KeyEvent.KEYCODE_F24) {
            return "f" + (keyCode - KeyEvent.KEYCODE_F13 + 13)
        }

        // Sayisal tuslar ve harfler -> tek karakter (KEYEVENTF_UNICODE)
        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
            return ('0' + (keyCode - KeyEvent.KEYCODE_0)).toString()
        }
        if (keyCode >= KeyEvent.KEYCODE_A && keyCode <= KeyEvent.KEYCODE_Z) {
            return ('A' + (keyCode - KeyEvent.KEYCODE_A)).toString()
        }
        if (keyCode >= KeyEvent.KEYCODE_NUMPAD_0 && keyCode <= KeyEvent.KEYCODE_NUMPAD_9) {
            return "numpad" + (keyCode - KeyEvent.KEYCODE_NUMPAD_0)
        }
        return null
    }
}