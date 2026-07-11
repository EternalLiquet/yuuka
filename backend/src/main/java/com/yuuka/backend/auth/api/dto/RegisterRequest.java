package com.yuuka.backend.auth.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
    @Email @NotBlank @Size(max = 320) String email,
    @NotBlank
        @Size(min = 12, max = 128)
        @Pattern(
            regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d).+$",
            message = "must include uppercase, lowercase, and numeric characters")
        String password,
    @Size(max = 120) String displayName) {}
