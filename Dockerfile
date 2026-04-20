FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /build
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN ./gradlew --version --no-daemon || true
COPY src ./src
RUN ./gradlew bootJar --no-daemon -x test

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd -r watchtower \
 && useradd -r -g watchtower -d /app watchtower \
 && mkdir -p /data && chown -R watchtower:watchtower /app /data
COPY --from=build /build/build/libs/*.jar /app/app.jar
USER watchtower
VOLUME ["/data"]
EXPOSE 9090
ENV JAVA_OPTS="-Xms256m -Xmx512m" \
    WATCHTOWER_DB_PATH=/data/watchtower.db
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD curl -fsS -o /dev/null http://localhost:9090/login || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
