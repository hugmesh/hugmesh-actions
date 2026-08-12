# Snapdragon Actions — multi-stage build.
#
# Stage 1: build the fat jar on a JDK image (Maven resolves deps in
# a separate cache-friendly layer).
# Stage 2: copy the jar onto a slim JRE so the runtime image stays
# under ~200 MB.

FROM eclipse-temurin:25-jdk AS build
# ── injected by scripts/patch-stack-src.sh (LOCAL stack cold build) ───────────
# This service depends on org-private systems.hugmesh:* artifacts but ships no
# settings.xml, so maven resolves from central only and the first goal fails.
# MAVEN_ARGS (Maven 3.9+) threads the injected settings into EVERY mvnw call,
# so no RUN line above needs rewriting.
ARG CI_API_V4_URL
ARG GITLAB_GROUP_ID
ENV CI_API_V4_URL=${CI_API_V4_URL} \
    GITLAB_GROUP_ID=${GITLAB_GROUP_ID} \
    MAVEN_ARGS="-s hugmesh-local-settings.xml"
WORKDIR /work
COPY mvnw mvnw.cmd ./
COPY .mvn .mvn
COPY pom.xml ./
COPY hugmesh-local-settings.xml ./
RUN ./mvnw -B dependency:go-offline
COPY src src
RUN ./mvnw -B package -DskipTests

FROM eclipse-temurin:25-jre
# curl is required by this image's docker-compose HEALTHCHECK (actuator/health/readiness);
# eclipse-temurin's jre base does not ship it, which silently breaks every
# `depends_on: condition: service_healthy` gate on a fresh compose boot.
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /work/target/*.jar app.jar
EXPOSE 8090
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
