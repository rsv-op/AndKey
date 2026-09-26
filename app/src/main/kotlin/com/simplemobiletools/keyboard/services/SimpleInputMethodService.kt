
import android.graphics.Region
import android.inputmethodservice.InputMethodService
import android.view.View

class SplitKeyboardService : InputMethodService() {

    private lateinit var leftCluster: View
    private lateinit var rightCluster: View

    override fun onCreateInputView(): View {
        val keyboardView = layoutInflater.inflate(R.layout.keyboard_view, null)
        leftCluster = keyboardView.findViewById(R.id.left_cluster)
        rightCluster = keyboardView.findViewById(R.id.right_cluster)
        return keyboardView
    }

    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)

        // 1. Tell Android to use a custom region for touch dispatching
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION

        // 2. Clear content/visible insets if you want the underlying app to display under the keyboard
        outInsets.contentTopInsets = window?.window?.decorView?.height ?: 0
        outInsets.visibleTopInsets = window?.window?.decorView?.height ?: 0

        // 3. Define the touchable boundaries strictly around the two thumb clusters
        val leftLocation = IntArray(2)
        val rightLocation = IntArray(2)

        leftCluster.getLocationInWindow(leftLocation)
        rightCluster.getLocationInWindow(rightLocation)

        // Bounding box for the left cluster
        val leftRegion = Region(
            leftLocation[0],
            leftLocation[1],
            leftLocation[0] + leftCluster.width,
            leftLocation[1] + leftCluster.height
        )

        // Bounding box for the right cluster
        val rightRegion = Region(
            rightLocation[0],
            rightLocation[1],
            rightLocation[0] + rightCluster.width,
            rightLocation[1] + rightCluster.height
        )

        // Combine both regions into outInsets.touchableRegion
        outInsets.touchableRegion.set(leftRegion)
        outInsets.touchableRegion.op(rightRegion, Region.Op.UNION)
    }
}
