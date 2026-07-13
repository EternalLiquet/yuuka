FROM eclipse-temurin:21-jdk-alpine AS build

RUN apk add --no-cache git

WORKDIR /workspace/app

COPY backend/gradlew backend/build.gradle.kts backend/settings.gradle.kts ./
COPY backend/gradle ./gradle
RUN chmod +x gradlew
RUN ./gradlew dependencies --no-daemon

COPY .git /workspace/.git
COPY backend/src ./src
ARG YUUKA_VERSION
RUN if [ -n "${YUUKA_VERSION:-}" ]; then \
      ./gradlew bootJar --no-daemon -PyuukaVersion="${YUUKA_VERSION}"; \
    else \
      ./gradlew bootJar --no-daemon; \
    fi

FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S yuuka && adduser -S yuuka -G yuuka
WORKDIR /app

COPY --from=build /workspace/app/build/libs/backend-*.jar app.jar

EXPOSE 8080
USER yuuka

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-Djava.io.tmpdir=/tmp", "-jar", "/app/app.jar"]
