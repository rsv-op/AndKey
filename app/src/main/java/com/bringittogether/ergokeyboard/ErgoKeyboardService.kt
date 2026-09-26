package com.bringittogether.ergokeyboard

import android.inputmethodservice.InputMethodService
import android.view.View
import androidx.compose.ui.platform.ComposeView

class ErgoKeyboardService : InputMethodService() {

    override fun onCreateInputView(): View {
        return ComposeView(this).apply {
            setContent {
                KeyboardLayout()
            }
        }
    }

    override fun onEvaluateFullscreenMode(): Boolean {
        return false 
    }
}
