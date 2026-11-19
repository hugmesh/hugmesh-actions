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

# ---- Java4Shell (CVE-2026-114938): patched Java 25 runtime ------------------------------
# 25.0.5.1 fixes the heap overflow in the Base64 AVX-512 intrinsic, but eclipse-temurin:25-jre
# on Docker Hub still ships the vulnerable 25.0.5+8. Adoptium built Temurin 25.0.5.1+2 for
# linux x64 only, so arm64 gets a JRE linked (jlink, same module set as the base JRE) from
# Amazon Corretto 25.0.5.1.2, the same 25.0.5.1 security release. Both downloads are pinned to
# the vendors' published sha256. The runtime stage swaps this JRE in only while its base JRE
# is older. Remove this stage and the swap once eclipse-temurin:25-jre itself is >= 25.0.5.1.
FROM eclipse-temurin:25-jre AS jre-cve-2026-114938
ARG JRE_X64_URL=https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.5.1%2B2/OpenJDK25U-jre_x64_linux_hotspot_25.0.5.1_2.tar.gz
ARG JRE_X64_SHA256=2edef9e4f81ec5ee426d023336d4cfbd7b4644e99884e7a73a6a867b70b17acf
ARG JDK_AARCH64_URL=https://corretto.aws/downloads/resources/25.0.5.1.2/amazon-corretto-25.0.5.1.2-linux-aarch64.tar.gz
ARG JDK_AARCH64_SHA256=9370d3e5bcf0d165e6be4a78c3f6b57160af16d3033319af583800251eb91df2
RUN set -eu; \
    mkdir -p /opt/jre; \
    arch="$(dpkg --print-architecture)"; \
    case "${arch}" in \
      amd64) url="${JRE_X64_URL}"; sum="${JRE_X64_SHA256}" ;; \
      arm64) url="${JDK_AARCH64_URL}"; sum="${JDK_AARCH64_SHA256}" ;; \
      *) echo "jre-cve-2026-114938: no patched build for ${arch}; keeping the base JRE"; exit 0 ;; \
    esac; \
    apt-get update; \
    apt-get install -y --no-install-recommends curl ca-certificates; \
    rm -rf /var/lib/apt/lists/*; \
    curl -fsSL --retry 3 -o /tmp/java.tar.gz "${url}"; \
    printf '%s  /tmp/java.tar.gz\n' "${sum}" > /tmp/java.sha256; \
    sha256sum -c /tmp/java.sha256; \
    mkdir /tmp/java; \
    tar -xzf /tmp/java.tar.gz -C /tmp/java --strip-components=1; \
    rmdir /opt/jre; \
    if [ "${arch}" = amd64 ]; then \
      mv /tmp/java /opt/jre; \
    else \
      java --list-modules > /tmp/modules; \
      modules="$(sed 's/@.*//' /tmp/modules | paste -sd, -)"; \
      [ -n "${modules}" ]; \
      /tmp/java/bin/jlink --add-modules "${modules}" --no-man-pages --no-header-files --output /opt/jre; \
      rm -rf /tmp/java /tmp/modules; \
    fi; \
    rm -f /tmp/java.tar.gz /tmp/java.sha256; \
    /opt/jre/bin/java -version

FROM eclipse-temurin:25-jre
# Java4Shell (CVE-2026-114938): use the patched JRE from the stage above while the base JRE
# is older than 25.0.5.1 (a symlink, so the JRE is not copied a second time).
COPY --from=jre-cve-2026-114938 /opt/jre/ /opt/java/jre-25.0.5.1/
RUN base="$(sed -n 's/^JAVA_VERSION="\(.*\)"$/\1/p' /opt/java/openjdk/release)" \
    && printf '%s\n25.0.5.1\n' "${base}" > /tmp/jre-versions \
    && if [ -x /opt/java/jre-25.0.5.1/bin/java ] && [ "${base}" != 25.0.5.1 ] \
          && sort -V -C /tmp/jre-versions; then \
         rm -rf /opt/java/openjdk && ln -s jre-25.0.5.1 /opt/java/openjdk && ldconfig; \
       else \
         rm -rf /opt/java/jre-25.0.5.1; \
       fi \
    && rm -f /tmp/jre-versions \
    && java -version
# curl is required by this image's docker-compose HEALTHCHECK (actuator/health/readiness);
# eclipse-temurin's jre base does not ship it, which silently breaks every
# `depends_on: condition: service_healthy` gate on a fresh compose boot.
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /work/target/*.jar app.jar
EXPOSE 8090
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
