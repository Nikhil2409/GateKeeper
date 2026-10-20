# syntax=docker/dockerfile:1
# Built inside Docker, so a fresh machine needs nothing but Docker. The Maven cache survives rebuilds.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -q -B dependency:go-offline
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -q -B package -DskipTests

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 gatekeeper
WORKDIR /app
COPY --from=build /build/target/*.jar app.jar
USER gatekeeper
EXPOSE 8082
# Small, fixed footprint (decision #66): heap = 40% of the container limit, C1-only JIT (~45 MB less native
# memory, some peak CPU throughput lost), metaspace, code cache and direct buffers capped
# glibc otherwise keeps a malloc arena per core (~60-80 MB of native memory on a 12-core machine)
ENV MALLOC_ARENA_MAX=2
ENTRYPOINT ["java", "-XX:+UseSerialGC", "-XX:MaxRAMPercentage=40", "-XX:TieredStopAtLevel=1", \
            "-XX:MaxMetaspaceSize=160m", "-XX:ReservedCodeCacheSize=32m", "-XX:MaxDirectMemorySize=48m", \
            "-Xss512k", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]
