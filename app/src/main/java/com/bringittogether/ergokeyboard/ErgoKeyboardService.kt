package com.bringittogether.ergokeyboard

import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.compose.ui.platform.ComposeView

class ErgoKeyboardService : InputMethodService() {

    override fun onCreateInputView(): View {
        val composeView = ComposeView(this).apply {
            setContent {
                // STAGE 5+: Compose UI tree and Voronoi hitbox logic goes here
            }
        }
        return composeView
    }

    // Prevent Android from forcing the keyboard into a solid fullscreen block in landscape
    override fun onEvaluateFullscreenMode(): Boolean {
        return false
    }

    // Disable fullscreen updates entirely to preserve the transparent window
    override fun updateFullscreenMode() {
        super.updateFullscreenMode()
    }
    
    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // Future configuration for precise touch passthrough (Insets) will bind here
    }
}
