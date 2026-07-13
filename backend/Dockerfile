FROM eclipse-temurin:21-jdk-alpine AS build

WORKDIR /workspace/app

COPY gradlew build.gradle.kts settings.gradle.kts ./
COPY gradle ./gradle
RUN chmod +x gradlew
RUN ./gradlew dependencies --no-daemon

COPY src ./src
ARG YUUKA_VERSION=0.0.0-dev
RUN ./gradlew bootJar --no-daemon -PyuukaVersion=${YUUKA_VERSION}

FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S yuuka && adduser -S yuuka -G yuuka
WORKDIR /app

COPY --from=build /workspace/app/build/libs/backend-*.jar app.jar

EXPOSE 8080
USER yuuka

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-Djava.io.tmpdir=/tmp", "-jar", "/app/app.jar"]
