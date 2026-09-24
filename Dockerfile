# Build stage
FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /build

COPY pom.xml .
COPY src ./src
COPY maven-settings.xml /root/.m2/settings.xml

RUN mvn clean package -DskipTests \
    -s /root/.m2/settings.xml \
    -Dmaven.wagon.http.ssl.insecure=true \
    -Dmaven.wagon.http.ssl.allowall=true \
    -Dmaven.wagon.http.ssl.ignore.validity.dates=true

# Runtime stage
FROM maven:3.9-eclipse-temurin-17

WORKDIR /app

RUN groupadd -g 1001 appgroup && \
    useradd -m -u 1001 -g appgroup appuser

COPY --from=builder /build/target/*.jar app.jar

RUN mkdir -p /data/uploads && \
    chown -R appuser:appgroup /data/uploads && \
    chown -R appuser:appgroup /app

USER appuser

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:8080/api/v1/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
