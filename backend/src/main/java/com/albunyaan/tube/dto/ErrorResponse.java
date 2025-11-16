package com.albunyaan.tube.dto;

import java.util.Map;

public class ErrorResponse {
    private String error;
    private String message;
    private Map<String, Object> details;

    public ErrorResponse() {}

    public ErrorResponse(String error, String message) {
        this.error = error;
        this.message = message;
        this.details = null;
    }

    public ErrorResponse(String error, String message, Map<String, Object> details) {
        this.error = error;
        this.message = message;
        this.details = details;
    }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public Map<String, Object> getDetails() { return details; }
    public void setDetails(Map<String, Object> details) { this.details = details; }
}
