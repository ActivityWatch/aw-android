package net.activitywatch.android

import android.graphics.Color
import android.view.ViewGroup
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Call after setContentView. The activity root owns safe areas for all its children. */
internal fun AppCompatActivity.applySafeWindowInsets() {
    // The native activities use a light theme even when the device uses dark mode.
    enableEdgeToEdge(
        statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.BLACK),
        navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.BLACK),
    )
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
