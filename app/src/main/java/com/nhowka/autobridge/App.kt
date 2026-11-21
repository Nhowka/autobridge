package com.nhowka.autobridge

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowInsets

/**
 * Recent Android (15+) draws apps edge to edge, so with our plain AppCompat action bar the top of every screen slid
 * under the title bar (and lists under the gesture bar). Instead of trusting how the insets are reported, each screen
 * is padded by the measured overlap: action bar bottom vs. content top, navigation bar vs. content bottom.
 * No-op on older Android (nothing overlaps).
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityPostCreated(activity: Activity, savedInstanceState: Bundle?) = fitContent(activity)
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    private fun fitContent(activity: Activity) {
        val content = activity.findViewById<View>(android.R.id.content) ?: return
        val decor = activity.window.decorView
        val base = intArrayOf(content.paddingLeft, content.paddingTop, content.paddingRight, content.paddingBottom)
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            val loc = IntArray(2)
            content.getLocationInWindow(loc)
            val contentTop = loc[1]
            val contentBottom = contentTop + content.height
            // Bottom of the action bar (or status bar when there is none).
            val bar = decor.findViewById<View>(androidx.appcompat.R.id.action_bar_container)
            val barBottom = if (bar != null && bar.isShown) IntArray(2).also { bar.getLocationInWindow(it) }[1] + bar.height
                else decor.rootWindowInsets?.getInsets(WindowInsets.Type.statusBars())?.top ?: 0
            val navTop = decor.height - (decor.rootWindowInsets?.getInsets(WindowInsets.Type.navigationBars())?.bottom ?: 0)
            // Padding doesn't move the view itself, so the needed padding is simply the overlap.
            val top = maxOf(0, barBottom - contentTop)
            val bottom = maxOf(0, contentBottom - navTop)
            if (content.paddingTop != base[1] + top || content.paddingBottom != base[3] + bottom)
                content.setPadding(base[0], base[1] + top, base[2], base[3] + bottom)
        }
        content.viewTreeObserver.addOnGlobalLayoutListener(listener)
    }
}
