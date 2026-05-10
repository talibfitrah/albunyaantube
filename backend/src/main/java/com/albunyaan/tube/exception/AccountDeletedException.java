package com.albunyaan.tube.exception;

public class AccountDeletedException extends RuntimeException {
    private final String uid;

    public AccountDeletedException(String uid) {
        super("Account does not exist: " + uid);
        this.uid = uid;
    }

    public String getUid() { return uid; }
}
