package com.albunyaan.tube.service;

public class AgeIneligibleAbortedException extends RuntimeException {
    public AgeIneligibleAbortedException(String uid, Throwable cause) {
        super("age-ineligible rejection aborted (revoke failed) for uid=" + uid, cause);
    }
}
