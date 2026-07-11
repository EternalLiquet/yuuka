package com.yuuka.backend.support;

import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "yuuka.auth.registration-enabled=true")
public abstract class AbstractIntegrationTest extends AbstractPostgresTest {}
