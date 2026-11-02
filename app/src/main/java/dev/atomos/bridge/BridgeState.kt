package dev.atomos.bridge

import java.lang.ref.WeakReference

/** In-process capability registry. The network bridge will call this dispatcher only
 * after pairing, authentication, permission checks and command-policy validation. */
object BridgeState {
    private var serviceRef: WeakReference<AtomosAccessibilityService>? = null
    fun attach(service: AtomosAccessibilityService) { serviceRef = WeakReference(service) }
    fun detach(service: AtomosAccessibilityService) {
        if (serviceRef?.get() === service) serviceRef = null
    }
    fun isAccessibilityConnected(): Boolean = serviceRef?.get() != null
    fun service(): AtomosAccessibilityService? = serviceRef?.get()
}

data class UiNodeSnapshot(
    val text: String?, val description: String?, val viewId: String?,
    val className: String?, val clickable: Boolean, val editable: Boolean,
    val boundsLeft: Int, val boundsTop: Int, val boundsRight: Int, val boundsBottom: Int,
    val children: List<UiNodeSnapshot>
)
