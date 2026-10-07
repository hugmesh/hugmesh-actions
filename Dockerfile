# syntax=docker/dockerfile:1.7
# HugMesh Actions — multi-stage build.
#
# Stage 1: build the fat jar on a JDK image (Maven resolves deps in
# a separate cache-friendly layer).
# Stage 2: copy the jar onto a slim JRE so the runtime image stays
# under ~200 MB.

FROM eclipse-temurin:25-jdk AS build

# PRIVATE DEPENDENCY (hugmesh-commons): this service depends on
# net.hugmesh:hugmesh-commons, which is NOT on Maven Central. The build stage
# resolves it through the COMMITTED settings.xml (the same file every sibling BFF
# ships): the GitLab group package registry, authenticated with CI_JOB_TOKEN.
# The token is a BuildKit SECRET (never an ARG, never an ENV, never a layer):
#
#   docker build --build-arg CI_API_V4_URL=... --build-arg GITLAB_GROUP_ID=... \
#                --secret id=ci_job_token,env=CI_JOB_TOKEN .
#
# The local compose stack passes only the two build args (its throwaway registry
# ignores auth). A GitHub Actions image build instead selects the GitHub Packages
# settings file, with the same secret id carrying a token that can read the
# packages:
#
#   --build-arg MAVEN_SETTINGS=.github/maven-ci-settings.xml \
#   --build-arg MAVEN_REGISTRY_ACTOR=<user> --secret id=ci_job_token,env=<token>
#
# This file used to be patched by hugmesh-generator/scripts/patch-stack-src.sh
# (hugmesh-local-settings.xml + MAVEN_ARGS), whose settings file was never
# committed, so a clean checkout died at the COPY. Shipping settings.xml and the
# ARG pair here makes that patcher a no-op for this repo.
ARG CI_API_V4_URL
ARG GITLAB_GROUP_ID
ARG MAVEN_SETTINGS=settings.xml
ARG MAVEN_REGISTRY_ACTOR
ENV CI_API_V4_URL=${CI_API_V4_URL} \
    GITLAB_GROUP_ID=${GITLAB_GROUP_ID} \
    MAVEN_REGISTRY_ACTOR=${MAVEN_REGISTRY_ACTOR}
WORKDIR /work
COPY mvnw mvnw.cmd ./
COPY .mvn .mvn
COPY pom.xml settings.xml ./
COPY .github/maven-ci-settings.xml .github/maven-ci-settings.xml
# Every maven call below is `-s settings.xml`; choosing the GitHub file just
# replaces that in-image copy (the checkout is untouched).
RUN if [ "${MAVEN_SETTINGS}" != "settings.xml" ]; then cp "${MAVEN_SETTINGS}" settings.xml; fi
# The token is exported only inside the RUN that reads the secret. No `-x`: a
# traced `export CI_JOB_TOKEN=...` would print it into the build log.
RUN --mount=type=secret,id=ci_job_token \
    sh -euc ' \
      if [ -f /run/secrets/ci_job_token ]; then \
        CI_JOB_TOKEN="$(cat /run/secrets/ci_job_token)"; export CI_JOB_TOKEN; \
      fi; \
      ./mvnw -B -s settings.xml dependency:go-offline'
COPY src src
RUN --mount=type=secret,id=ci_job_token \
    sh -euc ' \
      if [ -f /run/secrets/ci_job_token ]; then \
        CI_JOB_TOKEN="$(cat /run/secrets/ci_job_token)"; export CI_JOB_TOKEN; \
      fi; \
      ./mvnw -B -s settings.xml package -DskipTests'

FROM eclipse-temurin:25-jre
# curl is required by this image's docker-compose HEALTHCHECK (actuator/health/readiness);
# eclipse-temurin's jre base does not ship it, which silently breaks every
# `depends_on: condition: service_healthy` gate on a fresh compose boot.
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /work/target/*.jar app.jar
EXPOSE 8090
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
