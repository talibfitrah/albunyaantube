package com.albunyaan.tube.service;

public class ProfileAlreadyCompletedException extends RuntimeException {
    public ProfileAlreadyCompletedException(String uid) {
        super("profile already completed for uid=" + uid);
    }
}
