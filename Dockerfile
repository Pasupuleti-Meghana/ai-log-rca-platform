# ---------- build stage ----------
FROM maven:3.9.6-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests && cp target/ai-log-rca-platform-*.jar app.jar

# ---------- runtime stage ----------
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S logsense && adduser -S logsense -G logsense \
    && mkdir -p /var/lib/logsense/uploads && chown -R logsense:logsense /var/lib/logsense
USER logsense
WORKDIR /app
COPY --from=build /workspace/app.jar app.jar
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError" \
    LOGSENSE_UPLOAD_DIR=/var/lib/logsense/uploads
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health/liveness || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]