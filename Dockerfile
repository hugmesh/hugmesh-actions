# syntax=docker/dockerfile:1.7
# HugMesh Actions — multi-stage build.
#
# Stage 1: build the fat jar on a JDK image (Maven resolves deps in
# a separate cache-friendly layer).
# Stage 2: copy the jar onto a slim JRE so the runtime image stays
# under ~200 MB.

FROM eclipse-temurin:25-jdk AS build

# PRIVATE DEPENDENCY (hugmesh-commons): this service depends on
# net.hugmesh:hugmesh-commons, which is NOT on Maven Central. Two registry paths,
# chosen per build by which BuildKit secret is mounted. A secret is never an ARG,
# never an ENV, never a layer.
#
#  1. GITHUB ACTIONS release (.github/workflows/release.yml): the secret
#     `packages_read_token` (the PACKAGES_READ_TOKEN repository secret, a classic
#     PAT with read:packages, decision 90a = B in hugmesh-commons/RELEASING.md
#     section 6) selects .github/maven-ci-settings.xml, which resolves
#     hugmesh-commons and its parent hugmesh-parent from GitHub Packages.
#
#  2. EVERYTHING ELSE (GitLab CI, the local compose stack, a developer build): the
#     committed root settings.xml, the same file every sibling BFF ships, resolves
#     from the GitLab group registry. GitLab CI also passes the secret `ci_job_token`;
#     the local stack (tests/stack/m2-bootstrap.sh in hugmesh-generator) passes only
#     the two build args because its throwaway registry ignores auth.
#
#   docker build --build-arg CI_API_V4_URL=... --build-arg GITLAB_GROUP_ID=... \
#                --secret id=ci_job_token,env=CI_JOB_TOKEN .
#   docker build --secret id=packages_read_token,env=PACKAGES_READ_TOKEN .
#
# This file used to be patched by hugmesh-generator/scripts/patch-stack-src.sh
# (hugmesh-local-settings.xml + MAVEN_ARGS), whose settings file was never
# committed, so a clean checkout died at the COPY. Shipping settings.xml and the
# ARG pair here makes that patcher a no-op for this repo.
ARG CI_API_V4_URL
ARG GITLAB_GROUP_ID
ARG UPSTREAM_PACKAGES_ACTOR=github-actions
ENV CI_API_V4_URL=${CI_API_V4_URL} \
    GITLAB_GROUP_ID=${GITLAB_GROUP_ID}
WORKDIR /work
COPY mvnw mvnw.cmd ./
COPY .mvn .mvn
COPY pom.xml settings.xml ./
COPY .github/maven-ci-settings.xml .github/maven-ci-settings.xml
# Dependencies first, in their own cache layer. No `set -x`: it would echo the
# exported token into the build log. A packages_read_token that is mounted but EMPTY
# is an error, not a silent fall-back to the GitLab path (that would die later as a
# "Non-resolvable parent POM ... /groups//" and read as "unpublished").
RUN --mount=type=secret,id=ci_job_token \
    --mount=type=secret,id=packages_read_token \
    sh -euc '\
      settings=settings.xml; \
      if [ -e /run/secrets/packages_read_token ]; then \
        if [ ! -s /run/secrets/packages_read_token ]; then \
          echo "FATAL: secret packages_read_token is mounted but empty (is PACKAGES_READ_TOKEN set?)" >&2; \
          exit 1; \
        fi; \
        UPSTREAM_PACKAGES_TOKEN="$(cat /run/secrets/packages_read_token)"; \
        export UPSTREAM_PACKAGES_TOKEN UPSTREAM_PACKAGES_ACTOR; \
        settings=.github/maven-ci-settings.xml; \
      elif [ -f /run/secrets/ci_job_token ]; then \
        CI_JOB_TOKEN="$(cat /run/secrets/ci_job_token)"; \
        export CI_JOB_TOKEN; \
      fi; \
      echo "maven settings: ${settings}"; \
      ./mvnw -B -s "${settings}" dependency:go-offline'
COPY src src
RUN --mount=type=secret,id=ci_job_token \
    --mount=type=secret,id=packages_read_token \
    sh -euc '\
      settings=settings.xml; \
      if [ -e /run/secrets/packages_read_token ]; then \
        if [ ! -s /run/secrets/packages_read_token ]; then \
          echo "FATAL: secret packages_read_token is mounted but empty (is PACKAGES_READ_TOKEN set?)" >&2; \
          exit 1; \
        fi; \
        UPSTREAM_PACKAGES_TOKEN="$(cat /run/secrets/packages_read_token)"; \
        export UPSTREAM_PACKAGES_TOKEN UPSTREAM_PACKAGES_ACTOR; \
        settings=.github/maven-ci-settings.xml; \
      elif [ -f /run/secrets/ci_job_token ]; then \
        CI_JOB_TOKEN="$(cat /run/secrets/ci_job_token)"; \
        export CI_JOB_TOKEN; \
      fi; \
      echo "maven settings: ${settings}"; \
      ./mvnw -B -s "${settings}" package -DskipTests'

FROM eclipse-temurin:25-jre
# curl is required by this image's docker-compose HEALTHCHECK (actuator/health/readiness);
# eclipse-temurin's jre base does not ship it, which silently breaks every
# `depends_on: condition: service_healthy` gate on a fresh compose boot.
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /work/target/*.jar app.jar
EXPOSE 8090
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
