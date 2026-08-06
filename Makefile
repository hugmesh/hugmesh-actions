# hugmesh-actions developer Makefile.
#
# `make pentest` runs the SAME OWASP pen-test posture as the CI `pentest` stage
# (see .gitlab-ci.yml, which `include:`s the centralized
# Snapdragon-Systems/hugmesh-generator /tests/pentest/ci/pentest-stage.gitlab-ci.yml),
# but locally / repo-side so you can self-scan before pushing.
#
#   * SCA  — OWASP Dependency-Check over THIS Maven project (mirrors .pentest-sca).
#   * DAST — delegates to the generator pen-test harness (run-pentest.sh) against
#            $DAST_TARGET_URL when set (mirrors .pentest-dast-baseline).
#
# It is intentionally self-contained and non-hard-failing when no target/tooling
# is available, so it is safe to run on any dev box.

# Java/Maven dependency-check: fail the local build only on a critical (CVSS>=9)
# finding so `make pentest` is a usable pre-push gate (CI uses --failOnCVSS 7).
SCA_FAIL_CVSS ?= 9
# Pin the same JDK image the CI `test` job uses so the local scan matches CI
# (.gitlab-ci.yml's `test` job; also the pom.xml java.version / Dockerfile
# build stage — this project is Java 25 only, a 21 image fails the build).
JDK_IMAGE ?= eclipse-temurin:25-jdk-noble
# Where the DAST harness lives. If the generator repo is checked out next to this
# one (the Snapdragon-Systems org layout), it is found automatically; otherwise
# point PENTEST_HARNESS_DIR at a checkout, or the target prints the clone command.
PENTEST_HARNESS_DIR ?= ../hugmesh-generator/tests/pentest

.PHONY: pentest pentest-sca pentest-dast

## pentest: run the full local pen-test posture (SCA self-scan + optional DAST).
pentest: pentest-sca pentest-dast

## pentest-sca: OWASP Dependency-Check over this Maven project (mirrors CI .pentest-sca).
pentest-sca:
	@echo "==> [pentest:sca] OWASP Dependency-Check over this Maven project (failOnCVSS=$(SCA_FAIL_CVSS))"
	@if command -v docker >/dev/null 2>&1; then \
		echo "    running via $(JDK_IMAGE) (matches the CI test image)"; \
		docker run --rm -v "$(CURDIR):/workspace" -w /workspace $(JDK_IMAGE) \
			./mvnw -B org.owasp:dependency-check-maven:check -DfailBuildOnCVSS=$(SCA_FAIL_CVSS) \
		|| { echo "    [pentest:sca] dependency-check reported findings (or tooling unavailable) — see target/dependency-check-report.html"; exit 0; }; \
	elif [ -x ./mvnw ]; then \
		echo "    docker not found; running ./mvnw directly"; \
		./mvnw -B org.owasp:dependency-check-maven:check -DfailBuildOnCVSS=$(SCA_FAIL_CVSS) \
		|| { echo "    [pentest:sca] dependency-check reported findings (or tooling unavailable) — see target/dependency-check-report.html"; exit 0; }; \
	else \
		echo "    SKIP: neither docker nor ./mvnw available; cannot run SCA locally"; \
	fi

## pentest-dast: delegate to the generator pen-test harness against $DAST_TARGET_URL (mirrors CI .pentest-dast-baseline).
pentest-dast:
	@echo "==> [pentest:dast] authenticated ZAP baseline + probes + nuclei (full harness)"
	@if [ -z "$(DAST_TARGET_URL)" ]; then \
		echo "    SKIP: DAST_TARGET_URL is not set. To run DAST against a stood-up env:"; \
		echo "          DAST_TARGET_URL=https://host.test make pentest-dast"; \
	elif [ -f "$(PENTEST_HARNESS_DIR)/run-pentest.sh" ]; then \
		echo "    delegating to $(PENTEST_HARNESS_DIR)/run-pentest.sh (target=$(DAST_TARGET_URL))"; \
		bash "$(PENTEST_HARNESS_DIR)/run-pentest.sh" --env compose --scan baseline --pipeline main \
		|| { echo "    [pentest:dast] harness exited non-zero (findings or env-stand-up flake) — see out/pentest/"; exit 0; }; \
	else \
		echo "    harness not found at $(PENTEST_HARNESS_DIR). Clone the generator repo, e.g.:"; \
		echo "      git clone https://github.com/Snapdragon-Systems/hugmesh-generator ../hugmesh-generator"; \
		echo "    then re-run, or set PENTEST_HARNESS_DIR=/path/to/hugmesh-generator/tests/pentest"; \
		echo "    (CI runs this automatically via the included .pentest-dast-baseline job)"; \
	fi
