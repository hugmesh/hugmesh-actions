# Per-role access matrix for snapdragon-actions.
#
# Every role on the platform gets one scenario verifying the auth
# posture against the two protected endpoints:
#
#   /actuator/health                            — public (k8s probe).
#   /actuator/metrics                           — ROLE_GAME-MASTER only.
#   /api/registration/github-org-admin/evaluate — ROLE_ADMIN only.
#
# These match the SecurityConfig path-matchers added by item #10
# (infra-url-audit). A role drift here means the gate regressed.
Feature: Per-role access matrix — actions service
  As the actions service
  I must enforce role gates consistently for every Snapdragon role
  So a misconfigured token can't reach gated infra or registration paths.

  Background:
    Given the actions service is healthy

  Scenario: anonymous can reach /actuator/health but nothing else
    When the unauthenticated client GETs "/actuator/health"
    Then the response status is 200
    When the unauthenticated client GETs "/actuator/metrics"
    Then the response status is one of 401 or 403
    When the unauthenticated client POSTs to "/api/registration/github-org-admin/evaluate"
    Then the response status is one of 401 or 403

  Scenario: a plain player can reach health, NOT metrics, NOT registration
    Given "player" is authenticated with role "USER"
    When "player" GETs "/actuator/health"
    Then the response status is 200
    When "player" GETs "/actuator/metrics"
    Then the response status is 403
    When "player" POSTs to "/api/registration/github-org-admin/evaluate"
    Then the response status is 403

  Scenario: a producer cannot reach metrics or registration
    Given "producer" is authenticated with role "PRODUCER"
    When "producer" GETs "/actuator/metrics"
    Then the response status is 403
    When "producer" POSTs to "/api/registration/github-org-admin/evaluate"
    Then the response status is 403

  Scenario: a mod cannot reach metrics or registration
    Given "mod" is authenticated with role "MOD"
    When "mod" GETs "/actuator/metrics"
    Then the response status is 403
    When "mod" POSTs to "/api/registration/github-org-admin/evaluate"
    Then the response status is 403

  Scenario: a game-master CAN reach metrics; cannot reach registration
    Given "gm" is authenticated with role "GAME-MASTER"
    When "gm" GETs "/actuator/metrics"
    Then the response status is 200
    When "gm" POSTs to "/api/registration/github-org-admin/evaluate"
    Then the response status is 403

  Scenario: an admin CAN reach both metrics AND registration
    Given "admin" is authenticated with role "ADMIN"
    When "admin" GETs "/actuator/metrics"
    Then the response status is 200
    When "admin" POSTs to "/api/registration/github-org-admin/evaluate" with empty body
    Then the response status is one of 400 or 422 or 500
    # 4xx/500 expected — empty body fails the @Valid GrantRequest
    # parse. The point of this scenario is "admin is past the auth
    # gate"; deep request-shape validation lives in unit tests.
