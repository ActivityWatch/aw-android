package net.activitywatch.android

import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

internal fun isSystemNightMode(config: Configuration): Boolean =
    config.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

internal fun AppCompatActivity.isSystemNightMode(): Boolean =
    isSystemNightMode(resources.configuration)

/**
 * Paint the system-bar scrim and icon appearance to match the surface behind them.
 * Safe to call again when the embedded web UI switches theme.
 */
internal fun AppCompatActivity.applySystemBarAppearance(darkContent: Boolean) {
    if (darkContent) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
    } else {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.BLACK),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.BLACK),
        )
    }
    val color = ContextCompat.getColor(
        this,
        if (darkContent) R.color.chrome_dark else R.color.chrome_light,
    )
    window.setBackgroundDrawable(ColorDrawable(color))
    findViewById<ViewGroup>(android.R.id.content).getChildAt(0)?.setBackgroundColor(color)
}

/** Call after setContentView. The activity root owns safe areas for all its children. */
internal fun AppCompatActivity.applySafeWindowInsets() {
    // Native settings/onboarding stay light even when the device is in dark mode.
    // MainActivity overrides this once the embedded web UI reports its scheme.
    applySystemBarAppearance(darkContent = false)
    val root = findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
    val left = root.paddingLeft
    val top = root.paddingTop
    val right = root.paddingRight
    val bottom = root.paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
        val safe = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
                or WindowInsetsCompat.Type.ime()
        )
        view.setPadding(left + safe.left, top + safe.top, right + safe.right, bottom + safe.bottom)
        // In particular, DrawerLayout/NavigationView must not apply these a second time.
        WindowInsetsCompat.CONSUMED
    }
    ViewCompat.requestApplyInsets(root)
}
