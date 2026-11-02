package dev.atomos.bridge

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

class AtomosAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() { super.onServiceConnected(); BridgeState.attach(this) }
    override fun onDestroy() { BridgeState.detach(this); super.onDestroy() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun inspectVisibleUi(maxDepth: Int = 8): List<UiNodeSnapshot> {
        val budget = intArrayOf(40)
        val roots = windows.take(8).mapNotNull { it.root }.ifEmpty { listOfNotNull(rootInActiveWindow) }
        return roots.mapNotNull { node ->
            try { if (budget[0] <= 0) null else snapshot(node, 0, maxDepth.coerceIn(1, 12), budget) }
            finally { node.recycle() }
        }
    }
    private fun snapshot(node: AccessibilityNodeInfo, depth: Int, maxDepth: Int, budget: IntArray): UiNodeSnapshot {
        budget[0]--
        val rect = android.graphics.Rect(); node.getBoundsInScreen(rect)
        val children = if (depth >= maxDepth || budget[0] <= 0) emptyList() else buildList {
            for (i in 0 until node.childCount) {
                if (budget[0] <= 0) break
                val child = node.getChild(i) ?: continue
                try { add(snapshot(child, depth + 1, maxDepth, budget)) } finally { child.recycle() }
            }
        }
        return UiNodeSnapshot(node.text?.toString()?.take(1000), node.contentDescription?.toString()?.take(1000),
            node.viewIdResourceName?.take(300), node.className?.toString()?.take(200), node.isClickable,
            node.isEditable, rect.left, rect.top, rect.right, rect.bottom, children)
    }
    fun tap(x: Float, y: Float): Boolean = gesture(x,y,x,y,80)
    fun swipe(x1: Float,y1: Float,x2: Float,y2: Float,durationMs: Long=350): Boolean =
        gesture(x1,y1,x2,y2,durationMs.coerceIn(80,2000))
    private fun gesture(x1: Float,y1: Float,x2: Float,y2: Float,duration: Long): Boolean {
        if (!x1.isFinite() || !y1.isFinite() || !x2.isFinite() || !y2.isFinite()) return false
        val m=resources.displayMetrics
        val path=Path().apply { moveTo(x1.coerceIn(0f,m.widthPixels.toFloat()),y1.coerceIn(0f,m.heightPixels.toFloat())); lineTo(x2.coerceIn(0f,m.widthPixels.toFloat()),y2.coerceIn(0f,m.heightPixels.toFloat())) }
        return dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path,0,duration)).build(),null,null)
    }
    fun pressBack()=performGlobalAction(GLOBAL_ACTION_BACK)
    fun pressHome()=performGlobalAction(GLOBAL_ACTION_HOME)
    fun openNotifications()=performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
    fun typeIntoFocusedField(value:String):Boolean {
        if(value.length>4000)return false
        val node=findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?:return false
        return try { node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value) }) } finally { node.recycle() }
    }
    fun executeCloudCommand(type:String,p:JSONObject):JSONObject {
        val ok=when(type) {
            "inspect" -> {
                val roots=JSONArray()
                inspectVisibleUi(maxDepth = 5).forEach { roots.put(it.toJson()) }
                return JSONObject().put("ok",true).put("windows",roots)
            }
            "tap" -> tap(p.optDouble("x",Double.NaN).toFloat(),p.optDouble("y",Double.NaN).toFloat())
            "swipe" -> swipe(p.optDouble("x1",Double.NaN).toFloat(),p.optDouble("y1",Double.NaN).toFloat(),p.optDouble("x2",Double.NaN).toFloat(),p.optDouble("y2",Double.NaN).toFloat(),p.optLong("duration_ms",350))
            "back" -> pressBack()
            "home" -> pressHome()
            "notifications" -> openNotifications()
            "type" -> typeIntoFocusedField(p.optString("text").take(4000))
            else -> return JSONObject().put("ok",false).put("error","unsupported_command")
        }
        return JSONObject().put("ok",ok).put("error",if(ok) JSONObject.NULL else "action_not_dispatched")
    }
}
private fun UiNodeSnapshot.toJson():JSONObject {
    val childrenJson=JSONArray()
    children.forEach { childrenJson.put(it.toJson()) }
    return JSONObject().put("text",text).put("description",description).put("view_id",viewId)
        .put("class_name",className).put("clickable",clickable).put("editable",editable)
        .put("bounds",JSONArray(listOf(boundsLeft,boundsTop,boundsRight,boundsBottom))).put("children",childrenJson)
}