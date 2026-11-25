package app.inkask.tablet

import android.os.Bundle
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme

class MainActivity : ComponentActivity() {
    internal var answerPinch: AnswerPinch? = null

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val pinch = answerPinch ?: return super.dispatchTouchEvent(event)
        return pinch.dispatch(event) { super.dispatchTouchEvent(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(0xfff8f8f4.toInt(), 0xfff8f8f4.toInt()),
            navigationBarStyle = SystemBarStyle.light(0xfff8f8f4.toInt(), 0xfff8f8f4.toInt()),
        )
        val workspace = Workspace(applicationContext)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Green, background = Desk, surface = Paper, onBackground = Ink, onSurface = Ink)) {
                InkAsk(workspace)
            }
        }
    }
}
