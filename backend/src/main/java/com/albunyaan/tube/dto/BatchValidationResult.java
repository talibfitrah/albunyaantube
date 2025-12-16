package com.albunyaan.tube.dto;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Result of batch content validation against YouTube.
 *
 * Distinguishes between:
 * - Content that exists and was successfully fetched
 * - Content that definitely doesn't exist on YouTube (deleted, terminated, private)
 * - Content that couldn't be validated due to transient errors (network, rate limiting)
 *
 * @param <T> The type of content details (ChannelDetailsDto, PlaylistDetailsDto, StreamDetailsDto)
 */
public class BatchValidationResult<T> {

    /**
     * Content that exists on YouTube and was successfully fetched.
     * Key is the YouTube ID.
     */
    private final Map<String, T> valid;

    /**
     * YouTube IDs of content that definitely doesn't exist.
     * These threw ContentNotAvailableException or its subclasses:
     * - AccountTerminatedException (channel terminated)
     * - PrivateContentException (private content)
     * - AgeRestrictedContentException
     * - GeographicRestrictionException
     * - PaidContentException
     */
    private final Set<String> notFound;

    /**
     * YouTube IDs of content that couldn't be validated due to transient errors.
     * These should NOT be archived - validation should be retried later.
     * Examples: network timeouts, rate limiting, parsing errors.
     */
    private final Set<String> errors;

    /**
     * Error messages for items in the errors set.
     * Key is YouTube ID, value is error message.
     */
    private final Map<String, String> errorMessages;

    /**
     * YouTube IDs of content that was skipped due to circuit breaker opening mid-batch.
     * These items were NOT processed and should NOT have their status/lastValidatedAt updated.
     * They will be retried on the next validation run.
     */
    private final Set<String> skipped;

    public BatchValidationResult() {
        this.valid = Collections.synchronizedMap(new HashMap<>());
        this.notFound = Collections.synchronizedSet(new HashSet<>());
        this.errors = Collections.synchronizedSet(new HashSet<>());
        this.errorMessages = Collections.synchronizedMap(new HashMap<>());
        this.skipped = Collections.synchronizedSet(new HashSet<>());
    }

    public void addValid(String youtubeId, T content) {
        valid.put(youtubeId, content);
    }

    public void addNotFound(String youtubeId) {
        notFound.add(youtubeId);
    }

    public void addError(String youtubeId, String message) {
        errors.add(youtubeId);
        errorMessages.put(youtubeId, message);
    }

    /**
     * Mark an item as skipped (circuit breaker opened mid-batch).
     * Skipped items should NOT have their status/lastValidatedAt updated.
     */
    public void addSkipped(String youtubeId) {
        skipped.add(youtubeId);
    }

    public boolean isValid(String youtubeId) {
        return valid.containsKey(youtubeId);
    }

    public boolean isNotFound(String youtubeId) {
        return notFound.contains(youtubeId);
    }

    public boolean isError(String youtubeId) {
        return errors.contains(youtubeId);
    }

    public boolean isSkipped(String youtubeId) {
        return skipped.contains(youtubeId);
    }

    public T getContent(String youtubeId) {
        return valid.get(youtubeId);
    }

    public String getErrorMessage(String youtubeId) {
        return errorMessages.get(youtubeId);
    }

    public Map<String, T> getValid() {
        return Collections.unmodifiableMap(valid);
    }

    public Set<String> getNotFound() {
        return Collections.unmodifiableSet(notFound);
    }

    public Set<String> getErrors() {
        return Collections.unmodifiableSet(errors);
    }

    public Map<String, String> getErrorMessages() {
        return Collections.unmodifiableMap(errorMessages);
    }

    public Set<String> getSkipped() {
        return Collections.unmodifiableSet(skipped);
    }

    public int getValidCount() {
        return valid.size();
    }

    public int getNotFoundCount() {
        return notFound.size();
    }

    public int getErrorCount() {
        return errors.size();
    }

    public int getSkippedCount() {
        return skipped.size();
    }

    @Override
    public String toString() {
        return String.format("BatchValidationResult{valid=%d, notFound=%d, errors=%d, skipped=%d}",
                valid.size(), notFound.size(), errors.size(), skipped.size());
    }
}
