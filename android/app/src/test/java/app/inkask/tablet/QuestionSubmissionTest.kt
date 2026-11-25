package app.inkask.tablet

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestionSubmissionTest {
    @Test
    fun repeatedDraftAndSendShareIdentityButNewSelectionDoesNot() {
        val submission = QuestionSubmission()
        val log = JSONArray()
        val notes = JSONArray()
        val project = JSONObject().put("notes", notes)
        assertFalse(project.hasCommittedSubmission(submission.noteId, submission, true, null))
        notes.put(JSONObject().put("id", submission.noteId).put("branchQuote", "同一段文字").put("messages", log))
        assertTrue(project.hasCommittedSubmission(submission.noteId, submission, true, null))
        assertFalse(project.hasCommittedSubmission(submission.noteId, submission, false, null))
        log.put(JSONObject().put("id", submission.userId).put("role", "user"))
        log.put(JSONObject().put("id", submission.answerId).put("role", "assistant"))

        val afterRestart = JSONObject(project.toString())
        assertTrue(afterRestart.hasCommittedSubmission(submission.noteId, submission, false, null))
        val newSelection = QuestionSubmission()
        assertFalse(afterRestart.hasCommittedSubmission(newSelection.noteId, newSelection, false, null))
    }

    @Test
    fun staleRetryCannotReplaceANewerAttempt() {
        val submission = QuestionSubmission()
        val assistant = JSONObject().put("id", submission.answerId).put("role", "assistant")
            .put("createdAt", 10).put("status", "stopped")
        val note = JSONObject().put("id", submission.noteId).put("messages", JSONArray().put(assistant))
        val project = JSONObject().put("notes", JSONArray().put(note))
        val retry = AnswerRetry(submission.answerId, 10)
        assertFalse(project.hasCommittedSubmission(submission.noteId, submission, false, retry))
        assistant.put("createdAt", 11).put("status", "streaming")
        assertTrue(project.hasCommittedSubmission(submission.noteId, submission, false, retry))
        assistant.put("status", "complete")
        assertTrue(project.hasCommittedSubmission(submission.noteId, submission, false, AnswerRetry(submission.answerId, 11)))
    }
}
