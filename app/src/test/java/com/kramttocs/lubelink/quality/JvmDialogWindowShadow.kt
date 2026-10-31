package com.kramttocs.lubelink.quality

import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadows.ShadowWindowManagerImpl
import org.robolectric.util.reflector.Direct
import org.robolectric.util.reflector.ForType
import org.robolectric.util.reflector.Reflector

/**
 * JVM tests only (registered in robolectric.properties; the emulator does not use it). Under Robolectric a Compose
 * dialog window that wraps its content never becomes idle when the dialog holds a text field: its size is measured
 * again on every frame (reproduced with a plain AlertDialog and one OutlinedTextField, which hangs for 60 s
 * without this shadow and is idle in 25 ms with it). This lays out application windows at the screen's size instead,
 * as a phone's window manager does for the window itself; the dialog's own content still measures and centres as on
 * the phone. Nothing in the app changes.
 */
@Implements(className = "android.view.WindowManagerImpl", isInAndroidSdk = false)
class JvmDialogWindowShadow : ShadowWindowManagerImpl() {
    @field:RealObject lateinit var windowManager: Any

    private fun fullSize(params: ViewGroup.LayoutParams) {
        if (params is WindowManager.LayoutParams && params.type == WindowManager.LayoutParams.TYPE_APPLICATION) {
            params.width = ViewGroup.LayoutParams.MATCH_PARENT
            params.height = ViewGroup.LayoutParams.MATCH_PARENT
        }
    }

    @Implementation override fun addView(view: View, params: ViewGroup.LayoutParams) {
        fullSize(params)
        super.addView(view, params)
    }

    @Implementation fun updateViewLayout(view: View, params: ViewGroup.LayoutParams) {
        fullSize(params)
        Reflector.reflector(Real::class.java, windowManager).updateViewLayout(view, params)
    }

    @ForType(className = "android.view.WindowManagerImpl")
    interface Real { @Direct fun updateViewLayout(view: View, params: ViewGroup.LayoutParams) }
}
