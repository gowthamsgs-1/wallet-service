# ---- Build stage ---------------------------------------------------------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# Cache dependencies separately from source so code edits don't re-download the world
COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
RUN mvn -B clean package -DskipTests

# ---- Runtime stage -------------------------------------------------------
FROM eclipse-temurin:17-jre
WORKDIR /app

# Install curl as root for health check, then wipe apt cache to keep image slim
RUN apt-get update && \
    apt-get install -y --no-install-recommends curl && \
    rm -rf /var/lib/apt/lists/*

# Create and switch to non-root user
RUN useradd --system --create-home --shell /usr/sbin/nologin wallet
USER wallet

COPY --from=build /build/target/*.jar app.jar

EXPOSE 8080

# Health check runs as the 'wallet' user using the installed curl.
# Actuator uses base-path '/', so health is at /health (not /actuator/health).
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD curl -f http://localhost:8080/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]

