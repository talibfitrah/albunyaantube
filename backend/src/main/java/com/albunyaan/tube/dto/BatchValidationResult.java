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

    public BatchValidationResult() {
        this.valid = Collections.synchronizedMap(new HashMap<>());
        this.notFound = Collections.synchronizedSet(new HashSet<>());
        this.errors = Collections.synchronizedSet(new HashSet<>());
        this.errorMessages = Collections.synchronizedMap(new HashMap<>());
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

    public boolean isValid(String youtubeId) {
        return valid.containsKey(youtubeId);
    }

    public boolean isNotFound(String youtubeId) {
        return notFound.contains(youtubeId);
    }

    public boolean isError(String youtubeId) {
        return errors.contains(youtubeId);
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

    public int getValidCount() {
        return valid.size();
    }

    public int getNotFoundCount() {
        return notFound.size();
    }

    public int getErrorCount() {
        return errors.size();
    }

    @Override
    public String toString() {
        return String.format("BatchValidationResult{valid=%d, notFound=%d, errors=%d}",
                valid.size(), notFound.size(), errors.size());
    }
}
