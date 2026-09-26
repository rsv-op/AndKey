override fun onConfigureWindow(win: android.view.Window, isFullscreen: Boolean, isCandidatesOnly: Boolean) {
    super.onConfigureWindow(win, isFullscreen, isCandidatesOnly)
    win.apply {
        // Strip default solid background
        setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
    }
}
