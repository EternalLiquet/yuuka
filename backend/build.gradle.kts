plugins {
  java
  jacoco
  id("org.springframework.boot") version "3.5.3"
  id("io.spring.dependency-management") version "1.1.7"
  id("com.diffplug.spotless") version "8.8.0"
  id("info.solidsoft.pitest") version "1.19.0"
}

group = "com.yuuka"
version = "0.0.1-SNAPSHOT"
description = "Project Yuuka backend"

java {
  toolchain {
    languageVersion = JavaLanguageVersion.of(21)
  }
}

repositories {
  mavenCentral()
}

dependencies {
  implementation("org.springframework.boot:spring-boot-starter-actuator")
  implementation("org.springframework.boot:spring-boot-starter-data-jpa")
  implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
  implementation("org.springframework.boot:spring-boot-starter-security")
  implementation("org.springframework.boot:spring-boot-starter-validation")
  implementation("org.springframework.boot:spring-boot-starter-web")
  implementation("org.flywaydb:flyway-core")
  implementation("org.flywaydb:flyway-database-postgresql")
  implementation("org.springdoc:springdoc-openapi-starter-webmvc-api:2.8.9")
  runtimeOnly("org.postgresql:postgresql")
  testImplementation("org.springframework.boot:spring-boot-starter-test")
  testImplementation("org.springframework.boot:spring-boot-testcontainers")
  testImplementation("org.springframework.security:spring-security-test")
  testImplementation("org.testcontainers:junit-jupiter")
  testImplementation("org.testcontainers:postgresql")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

spotless {
  java {
    importOrder()
    removeUnusedImports()
    googleJavaFormat()
  }
  kotlinGradle {
    ktlint()
  }
}

tasks.withType<Test> {
  useJUnitPlatform()
  finalizedBy(tasks.jacocoTestReport)
}

jacoco {
  toolVersion = "0.8.13"
}

tasks.jacocoTestReport {
  dependsOn(tasks.test)
  reports {
    xml.required.set(true)
    html.required.set(true)
  }
}

tasks.jacocoTestCoverageVerification {
  dependsOn(tasks.test)
  violationRules {
    rule {
      limit {
        counter = "LINE"
        value = "COVEREDRATIO"
        minimum = "0.80".toBigDecimal()
      }
    }
    rule {
      element = "CLASS"
      includes =
        listOf(
          "com.yuuka.backend.paycheck.domain.PaycheckCalculator",
          "com.yuuka.backend.bucket.domain.BucketCalculator",
        )
      limit {
        counter = "LINE"
        value = "COVEREDRATIO"
        minimum = "0.90".toBigDecimal()
      }
      limit {
        counter = "BRANCH"
        value = "COVEREDRATIO"
        minimum = "0.85".toBigDecimal()
      }
    }
    rule {
      element = "CLASS"
      includes =
        listOf(
          "com.yuuka.backend.audit.application.AuditService",
          "com.yuuka.backend.auth.application.AuthRateLimitService",
          "com.yuuka.backend.auth.application.AuthService",
          "com.yuuka.backend.auth.application.RefreshTokenService",
          "com.yuuka.backend.auth.application.TotpService",
          "com.yuuka.backend.bucket.application.BucketTransactionService",
          "com.yuuka.backend.paycheck.application.PaycheckService",
          "com.yuuka.backend.template.application.TemplateService",
        )
      limit {
        counter = "LINE"
        value = "COVEREDRATIO"
        minimum = "0.90".toBigDecimal()
      }
      limit {
        counter = "BRANCH"
        value = "COVEREDRATIO"
        minimum = "0.85".toBigDecimal()
      }
    }
  }
}

tasks.check {
  dependsOn(tasks.jacocoTestCoverageVerification)
}

pitest {
  targetClasses.set(
    setOf(
      "com.yuuka.backend.paycheck.domain.PaycheckCalculator",
      "com.yuuka.backend.bucket.domain.BucketCalculator",
      "com.yuuka.backend.paycheck.domain.PaycheckVisibilityPolicy",
      "com.yuuka.backend.paycheck.domain.StatusTransitionPolicy",
    ),
  )
  targetTests.set(
    setOf(
      "com.yuuka.backend.paycheck.domain.PaycheckCalculatorTests",
      "com.yuuka.backend.bucket.domain.BucketCalculatorTests",
      "com.yuuka.backend.paycheck.domain.PaycheckVisibilityPolicyTests",
      "com.yuuka.backend.paycheck.domain.StatusTransitionPolicyTests",
    ),
  )
  junit5PluginVersion.set("1.2.3")
  jvmPath.set(
    javaToolchains
      .launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }
      .map { it.executablePath },
  )
  threads.set(2)
  outputFormats.set(setOf("XML", "HTML"))
  timestampedReports.set(false)
  mutationThreshold.set(85)
}

tasks.register<JavaExec>("printPasswordHash") {
  group = "security"
  description = "Prints a BCrypt hash from YUUKA_PASSWORD_TO_HASH or -Ppassword=..."
  classpath = sourceSets["main"].runtimeClasspath
  mainClass.set("com.yuuka.backend.tools.PasswordHashTool")
  javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
  val password = providers.gradleProperty("password")
  if (password.isPresent) {
    args(password.get())
  }
}

tasks.register<JavaExec>("printTotpSecret") {
  group = "security"
  description = "Prints a TOTP secret and authenticator URI for YUUKA_OWNER_EMAIL or -Pemail=..."
  classpath = sourceSets["main"].runtimeClasspath
  mainClass.set("com.yuuka.backend.tools.TotpSecretTool")
  javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
  val email = providers.gradleProperty("email")
  if (email.isPresent) {
    args(email.get())
  }
}
