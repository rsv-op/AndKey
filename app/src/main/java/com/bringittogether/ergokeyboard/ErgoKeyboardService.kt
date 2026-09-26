package com.bringittogether.ergokeyboard

import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.compose.ui.platform.ComposeView

class ErgoKeyboardService : InputMethodService() {

    override fun onCreateInputView(): View {
        val composeView = ComposeView(this).apply {
            setContent {
                // STAGE 5: The UI will receive these input commands
                // KeyboardLayout(
                //     onKeyPress = { commitText(it) },
                //     onBackspace = { sendDelete() },
                //     onEnter = { sendEnter() }
                // )
            }
        }
        return composeView
    }

    // --- Core Input Connection Methods ---

    /**
     * Commits standard text to the active input field.
     */
    fun commitText(text: String) {
        currentInputConnection?.commitText(text, 1)
    }

    /**
     * Deletes the character immediately preceding the cursor.
     */
    fun sendDelete() {
        currentInputConnection?.deleteSurroundingText(1, 0)
    }

    /**
     * Sends a hardware-level Enter key event to trigger IME actions (Search, Go, Next, Enter).
     */
    fun sendEnter() {
        val eventTime = SystemClock.uptimeMillis()
        currentInputConnection?.sendKeyEvent(
            KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE)
        )
        currentInputConnection?.sendKeyEvent(
            KeyEvent(eventTime, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE)
        )
    }

    // --- Window Constraints ---

    override fun onEvaluateFullscreenMode(): Boolean {
        return false // Prevents full-screen extraction in landscape mode
    }

    override fun updateFullscreenMode() {
        super.updateFullscreenMode()
    }
}
