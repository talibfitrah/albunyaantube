package com.albunyaan.tube.dto;

/**
 * Payload for the self-service PATCH .../{id}/submitter-note endpoints. A submitter
 * (moderator or admin who owns the row) can attach or revise a free-text "why I'm
 * suggesting this" note while their submission is still PENDING or REQUEST_CHANGES.
 *
 * A null or blank value clears the note.
 */
public class SubmitterNoteUpdateRequest {

    private String submitterNote;

    public String getSubmitterNote() {
        return submitterNote;
    }

    public void setSubmitterNote(String submitterNote) {
        this.submitterNote = submitterNote;
    }
}
