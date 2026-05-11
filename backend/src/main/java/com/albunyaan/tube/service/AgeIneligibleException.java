package com.albunyaan.tube.service;

public class AgeIneligibleException extends RuntimeException {
    private final int age;

    public AgeIneligibleException(String uid, int age) {
        super("age-ineligible: uid=" + uid + " age=" + age);
        this.age = age;
    }

    public int getAge() { return age; }
}
