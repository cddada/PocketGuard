package com.pocketguard.app

import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

class TouchOverlay(private val context: Context) {

    private var overlayView: View? = null
    private var windowManager: WindowManager? = null
    private var isActive = false

    var onTouchDetected: (() -> Unit)? = null

    fun enable() {
        if (isActive) return
        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "Overlay permission is not available")
            return
        }

        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        overlayView = object : View(context) {
            override fun onTouchEvent(event: MotionEvent): Boolean {
                if (event.action == MotionEvent.ACTION_DOWN) {
                    onTouchDetected?.invoke()
                }
                return true
            }
        }

        try {
            windowManager?.addView(overlayView, params)
            isActive = true
        } catch (e: Exception) {
            Log.e(TAG, "Unable to add touch overlay", e)
        }
    }

    fun disable() {
        if (!isActive) return
        try {
            overlayView?.let { windowManager?.removeView(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Unable to remove touch overlay", e)
        }
        overlayView = null
        isActive = false
    }

    fun isEnabled() = isActive

    companion object {
        private const val TAG = "TouchOverlay"
    }
}
