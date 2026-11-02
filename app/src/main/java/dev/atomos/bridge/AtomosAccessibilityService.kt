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
    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* Event stream intentionally not persisted. */ }
    override fun onInterrupt() {}

    fun inspectVisibleUi(maxDepth: Int = 8): List<UiNodeSnapshot> {
        val roots = windows.mapNotNull { it.root }.ifEmpty { listOfNotNull(rootInActiveWindow) }
        return roots.map { snapshot(it, 0, maxDepth) }
    }

    private fun snapshot(node: AccessibilityNodeInfo, depth: Int, maxDepth: Int): UiNodeSnapshot {
        val rect = android.graphics.Rect(); node.getBoundsInScreen(rect)
        val children = if (depth >= maxDepth) emptyList() else (0 until node.childCount).mapNotNull { i ->
            node.getChild(i)?.let { child -> try { snapshot(child, depth + 1, maxDepth) } finally { child.recycle() } }
        }
        return UiNodeSnapshot(
            node.text?.toString(), node.contentDescription?.toString(), node.viewIdResourceName,
            node.className?.toString(), node.isClickable, node.isEditable,
            rect.left, rect.top, rect.right, rect.bottom, children
        )
    }

    fun tap(x: Float, y: Float): Boolean = gesture(x, y, x, y, 80)
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 350): Boolean =
        gesture(x1, y1, x2, y2, durationMs.coerceIn(80, 2000))

    private fun gesture(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long): Boolean {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
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
            val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } finally { node.recycle() }
    }
}
