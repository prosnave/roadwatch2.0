package com.roadwatch.ui

import android.graphics.Color
import android.view.View
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.Snackbar
import com.roadwatch.app.R

object UiAlerts {
    fun success(anchor: View?, text: String) {
        show(anchor, text, R.color.alert_success)
    }
    fun error(anchor: View?, text: String) {
        show(anchor, text, R.color.alert_error)
    }
    fun info(anchor: View?, text: String) {
        show(anchor, text, R.color.alert_info)
    }
    fun warn(anchor: View?, text: String) {
        show(anchor, text, R.color.alert_warn)
    }

    private fun show(anchor: View?, text: String, colorRes: Int) {
        // Try to find a stable root to anchor the Snackbar, so it survives sheet dismissals
        val rootCandidate: View? = anchor?.rootView
            ?: ((anchor?.context as? android.app.Activity)?.findViewById(android.R.id.content) as? View)
        val attach = rootCandidate ?: return
        val sb = Snackbar.make(attach, text, Snackbar.LENGTH_LONG)
        try {
            val ctx = attach.context
            sb.setBackgroundTint(ContextCompat.getColor(ctx, colorRes))
            sb.setTextColor(Color.WHITE)
            if (anchor != null) {
                sb.setAnchorView(anchor)
            }
        } catch (_: Exception) {}
        sb.show()
    }
}
