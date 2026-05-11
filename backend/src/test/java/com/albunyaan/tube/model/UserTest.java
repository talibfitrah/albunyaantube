package com.albunyaan.tube.model;

import com.google.cloud.Timestamp;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UserTest {

    @Test
    void copyPreservesDateOfBirth() {
        Timestamp dob = Timestamp.ofTimeSecondsAndNanos(946684800L, 0); // 2000-01-01
        User u = new User("uid-1", "a@b.com", "Alice", "user");
        u.setDateOfBirth(dob);
        User copy = u.copy();
        assertEquals(dob, copy.getDateOfBirth());
    }

    @Test
    void newUserHasNullDateOfBirth() {
        User u = new User();
        assertNull(u.getDateOfBirth());
    }
}
