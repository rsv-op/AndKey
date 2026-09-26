package com.bringittogether.ergokeyboard

import android.inputmethodservice.InputMethodService
import android.view.View
import androidx.compose.ui.platform.ComposeView

class ErgoKeyboardService : InputMethodService() {

    override fun onCreateInputView(): View {
        val composeView = ComposeView(this).apply {
            setContent {
                // Placeholder: Compose UI tree goes here (STAGE 5+)
            }
        }
        
        return composeView
    }
}
