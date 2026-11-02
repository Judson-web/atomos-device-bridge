package dev.atomos.bridge

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class AtomosAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() { super.onServiceConnected(); BridgeState.attach(this) }
    override fun onDestroy() { BridgeState.detach(this); super.onDestroy() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* Never retain event content. */ }
    override fun onInterrupt() {}

    /** Bounded snapshot: at most eight windows, 500 nodes and 1,000 chars per text field. */
    fun inspectVisibleUi(maxDepth: Int = 8): List<UiNodeSnapshot> {
        val budget = intArrayOf(500)
        val roots = windows.take(8).mapNotNull { it.root }.ifEmpty { listOfNotNull(rootInActiveWindow) }
        return roots.mapNotNull { node ->
            try {
                if (budget[0] <= 0) null else snapshot(node, 0, maxDepth.coerceIn(1, 12), budget)
            } finally {
                node.recycle()
            }
        }
    }

    private fun snapshot(node: AccessibilityNodeInfo, depth: Int, maxDepth: Int, budget: IntArray): UiNodeSnapshot {
        budget[0]--
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        val children = if (depth >= maxDepth || budget[0] <= 0) emptyList() else buildList {
            for (i in 0 until node.childCount) {
                if (budget[0] <= 0) break
                val child = node.getChild(i) ?: continue
                try { add(snapshot(child, depth + 1, maxDepth, budget)) }
                finally { child.recycle() }
            }
        }
        return UiNodeSnapshot(
            node.text?.toString()?.take(1000),
            node.contentDescription?.toString()?.take(1000),
            node.viewIdResourceName?.take(300),
            node.className?.toString()?.take(200),
            node.isClickable, node.isEditable,
            rect.left, rect.top, rect.right, rect.bottom, children
        )
    }

    fun tap(x: Float, y: Float): Boolean = gesture(x, y, x, y, 80)

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 350): Boolean =
        gesture(x1, y1, x2, y2, durationMs.coerceIn(80, 2000))

    private fun gesture(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long): Boolean {
        if (!x1.isFinite() || !y1.isFinite() || !x2.isFinite() || !y2.isFinite()) return false
        val metrics = resources.displayMetrics
        val sx1 = x1.coerceIn(0f, metrics.widthPixels.toFloat())
        val sy1 = y1.coerceIn(0f, metrics.heightPixels.toFloat())
        val sx2 = x2.coerceIn(0f, metrics.widthPixels.toFloat())
        val sy2 = y2.coerceIn(0f, metrics.heightPixels.toFloat())
        val path = Path().apply { moveTo(sx1, sy1); lineTo(sx2, sy2) }
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    fun pressBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun pressHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun openNotifications(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

    fun typeIntoFocusedField(value: String): Boolean {
        if (value.length > 4000) return false
        val node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        return try {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } finally {
            node.recycle()
        }
    }
}
