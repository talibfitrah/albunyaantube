package com.albunyaan.tube.service;

public class UserNotFoundException extends RuntimeException {
    public UserNotFoundException(String uid) { super("user not found: " + uid); }
}
