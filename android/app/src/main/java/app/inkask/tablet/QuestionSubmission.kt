package app.inkask.tablet

import org.json.JSONObject
import java.util.UUID

internal data class QuestionSubmission(
    val noteId: String = UUID.randomUUID().toString(),
    val userId: String = UUID.randomUUID().toString(),
    val answerId: String = UUID.randomUUID().toString(),
)

internal data class AnswerRetry(val id: String, val createdAt: Long) {
    fun matches(message: JSONObject): Boolean =
        message.optString("id") == id && message.optLong("createdAt") == createdAt &&
            message.optString("role") == "assistant" &&
            message.optString("status") in setOf("stopped", "streaming", "error")
}

internal fun JSONObject.hasCommittedSubmission(
    noteId: String, submission: QuestionSubmission, saveOnly: Boolean, retry: AnswerRetry?, draftMessageCount: Int? = null,
): Boolean {
    val note = optJSONArray("notes").objects().firstOrNull { it.optString("id") == noteId }
        ?: return retry != null
    val messages = note.optJSONArray("messages").objects()
    return when {
        retry != null -> messages.lastOrNull()?.let(retry::matches) != true
        draftMessageCount != null && messages.size != draftMessageCount -> true
        draftMessageCount != null && saveOnly -> note.optString("draftSaveToken") == submission.userId
        saveOnly -> true
        else -> messages.any { it.optString("id") == submission.userId || it.optString("id") == submission.answerId }
    }
}
