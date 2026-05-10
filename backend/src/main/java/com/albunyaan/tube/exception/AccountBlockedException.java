package com.albunyaan.tube.exception;

public class AccountBlockedException extends RuntimeException {
    private final String uid;
    private final String reason;

    public AccountBlockedException(String uid, String reason) {
        super("Account is blocked: " + uid);
        this.uid = uid;
        this.reason = reason;
    }

    public String getUid() { return uid; }
    public String getReason() { return reason; }
}
