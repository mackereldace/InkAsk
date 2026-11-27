package app.inkask.tablet

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import org.json.JSONArray

@Composable
internal fun rememberReaderHistory(): ReaderHistory {
    val context = LocalContext.current.applicationContext
    return remember(context) { ReaderHistory(context.getSharedPreferences("reader-library", Context.MODE_PRIVATE)) }
}

internal class ReaderHistory(private val preferences: SharedPreferences) {
    var documents by mutableStateOf(runCatching { JSONArray(preferences.getString("recent", "[]")).strings() }.getOrDefault(emptyList()))
        private set
    var showTree by mutableStateOf(preferences.getBoolean("tree", true))
        private set

    fun recordOpen(id: String) {
        if (documents.firstOrNull() == id) return
        documents = listOf(id) + documents.filterNot { it == id }
        preferences.edit().putString("recent", JSONArray(documents).toString()).apply()
    }

    fun setTree(value: Boolean) {
        showTree = value
        preferences.edit().putBoolean("tree", value).apply()
    }
}
