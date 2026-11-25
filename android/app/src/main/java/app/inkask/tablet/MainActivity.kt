package app.inkask.tablet

import android.os.Bundle
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : ComponentActivity() {
    internal var answerPinch: AnswerPinch? = null
    internal var composerOutsideTap: ComposerOutsideTap? = null

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val outsideTap = composerOutsideTap
        val dismissComposer = outsideTap?.isOutsideTap(event) == true
        val handled = answerPinch?.dispatch(event) { super.dispatchTouchEvent(it) } ?: super.dispatchTouchEvent(event)
        if (dismissComposer && composerOutsideTap === outsideTap) outsideTap?.dismiss?.invoke()
        return handled
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(0xfff8f8f4.toInt(), 0xfff8f8f4.toInt()),
            navigationBarStyle = SystemBarStyle.light(0xfff8f8f4.toInt(), 0xfff8f8f4.toInt()),
        )
        hideSystemBars()
        val workspace = Workspace(applicationContext)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Green, background = Desk, surface = Paper, onBackground = Ink, onSurface = Ink)) {
                InkAsk(workspace)
            }
        }
    }
}
