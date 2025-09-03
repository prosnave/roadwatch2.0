package com.roadwatch.app.ui.insets

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

fun View.applySystemBarInsets(
    applyTop: Boolean = true,
    applyBottom: Boolean = true,
    extraBottom: Int = 0,
    extraTop: Int = 0
) {
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        v.setPadding(
            v.paddingLeft,
            (if (applyTop) sys.top else v.paddingTop) + extraTop,
            v.paddingRight,
            (if (applyBottom) sys.bottom else v.paddingBottom) + extraBottom
        )
        insets
    }
    requestApplyInsetsWhenAttached()
}

fun View.requestApplyInsetsWhenAttached() {
    if (isAttachedToWindow) {
        requestApplyInsets()
    } else {
        addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                v.removeOnAttachStateChangeListener(this)
                v.requestApplyInsets()
            }

            override fun onViewDetachedFromWindow(v: View) {}
        })
    }
}
