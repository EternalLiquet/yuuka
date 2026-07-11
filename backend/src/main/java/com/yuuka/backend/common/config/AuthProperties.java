package com.yuuka.backend.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "yuuka.auth")
public record AuthProperties(boolean registrationEnabled) {}
