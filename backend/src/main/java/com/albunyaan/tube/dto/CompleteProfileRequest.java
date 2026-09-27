package com.albunyaan.tube.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public class CompleteProfileRequest {

    @NotBlank
    @Size(max = 40)
    private String displayName;

    @NotNull
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate dateOfBirth;

    /** Optional (owner ruling 2026-09-27): null when absent; the pattern applies only to a given number. */
    @Pattern(regexp = "^\\+[1-9]\\d{7,14}$", message = "must be E.164 format")
    private String phoneNumber;

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String s) { this.displayName = s; }
    public LocalDate getDateOfBirth() { return dateOfBirth; }
    public void setDateOfBirth(LocalDate d) { this.dateOfBirth = d; }
    public String getPhoneNumber() { return phoneNumber; }
    public void setPhoneNumber(String s) { this.phoneNumber = (s == null || s.isBlank()) ? null : s; }
}
