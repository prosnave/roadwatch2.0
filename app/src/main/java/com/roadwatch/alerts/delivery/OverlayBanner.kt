package com.roadwatch.alerts.delivery

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.roadwatch.app.R
import com.roadwatch.data.HazardType
import com.roadwatch.engine.Severity

/**
 * A non-interactive banner drawn above whatever app is in front (Maps, Waze, YouTube...), using the
 * "Display over other apps" permission. It never takes focus or touches, so it can't interfere with
 * navigation; it just appears at the top of the screen, counts down the distance and disappears.
 */
class OverlayBanner(context: Context) {
    private val ctx = context.applicationContext
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var root: LinearLayout? = null
    private var titleView: TextView? = null
    private var subtitleView: TextView? = null
    private var iconView: ImageView? = null
    private val autoHide = Runnable { hide() }

    val isShowing: Boolean get() = root != null

    fun canShow(): Boolean = Settings.canDrawOverlays(ctx)

    /** Must be called on the main thread. */
    fun show(title: String, subtitle: String, severity: Severity, type: HazardType?, holdMs: Long = 12_000L) {
        if (!canShow()) return
        try {
            if (root == null) build()
            apply(title, subtitle, severity, type)
            handler.removeCallbacks(autoHide)
            handler.postDelayed(autoHide, holdMs)
        } catch (e: Exception) {
            // BadTokenException / SecurityException when permission is revoked mid-drive.
            Log.w(TAG, "Overlay failed", e)
            root = null
        }
    }

    fun updateSubtitle(text: String) {
        subtitleView?.text = text
    }

    fun hide() {
        handler.removeCallbacks(autoHide)
        root?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        root = null
        titleView = null
        subtitleView = null
        iconView = null
    }

    private fun dp(v: Int) = (ctx.resources.displayMetrics.density * v).toInt()

    private fun build() {
        val r = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(20), dp(12))
        }
        val icon = ImageView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(14) }
            setColorFilter(Color.WHITE)
        }
        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val title = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val subtitle = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        }
        texts.addView(title)
        texts.addView(subtitle)
        r.addView(icon)
        r.addView(texts)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = statusBarHeight() + dp(8)
            horizontalMargin = 0.03f
        }
        wm.addView(r, lp)
        root = r
        titleView = title
        subtitleView = subtitle
        iconView = icon
    }

    private fun apply(title: String, subtitle: String, severity: Severity, type: HazardType?) {
        titleView?.text = title
        subtitleView?.text = subtitle
        val color = when (severity) {
            Severity.WARNING -> Color.parseColor("#D32F2F")
            Severity.CAUTION -> Color.parseColor("#E65100")
            Severity.INFO -> Color.parseColor("#1565C0")
        }
        root?.background = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(18).toFloat()
        }
        val res = when (type) {
            HazardType.SPEED_BUMP -> R.drawable.ic_hazard_bump
            HazardType.POTHOLE -> R.drawable.ic_hazard_pothole
            HazardType.RUMBLE_STRIP -> R.drawable.ic_hazard_rumble_strip
            else -> R.drawable.ic_hazard
        }
        iconView?.setImageResource(res)
        // ic_hazard is already red/white; tint only the monochrome glyphs.
        if (type == null || type == HazardType.SPEED_LIMIT_ZONE) iconView?.clearColorFilter() else iconView?.setColorFilter(Color.WHITE)
    }

    private fun statusBarHeight(): Int {
        val id = ctx.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) ctx.resources.getDimensionPixelSize(id) else dp(24)
    }

    companion object {
        private const val TAG = "OverlayBanner"
    }
}
