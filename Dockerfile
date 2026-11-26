# HaulMate API + admin panel. Credentials come from .env at `docker run` time (never baked in).
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q -DskipTests dependency:go-offline || true
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:25-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl tzdata && rm -rf /var/lib/apt/lists/* \
    && useradd -r -u 1001 -g root haulmate
ENV TZ=Africa/Harare PORT=8670 STORAGE_DIR=/app/data/uploads \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
WORKDIR /app
COPY --from=build /src/target/*.jar /app/app.jar
RUN mkdir -p /app/data/uploads && chown -R haulmate:root /app
USER haulmate
EXPOSE 8670
HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --retries=5 \
  CMD curl -fs http://localhost:${PORT}/actuator/health || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
