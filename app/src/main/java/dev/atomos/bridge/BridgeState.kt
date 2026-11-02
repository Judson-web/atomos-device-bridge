package dev.atomos.bridge
import java.lang.ref.WeakReference
object BridgeState {
    @Volatile private var serviceRef:WeakReference<AtomosAccessibilityService>?=null
    fun attach(service:AtomosAccessibilityService){serviceRef=WeakReference(service)}
    fun detach(service:AtomosAccessibilityService){if(serviceRef?.get()===service)serviceRef=null}
    fun isAccessibilityConnected()=serviceRef?.get()!=null
    fun service()=serviceRef?.get()
}
data class UiNodeSnapshot(val text:String?,val description:String?,val viewId:String?,val className:String?,val clickable:Boolean,val editable:Boolean,val boundsLeft:Int,val boundsTop:Int,val boundsRight:Int,val boundsBottom:Int,val children:List<UiNodeSnapshot>)