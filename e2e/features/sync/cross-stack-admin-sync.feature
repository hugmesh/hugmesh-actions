# Cross-stack admin sync — the headline test for snapdragon-actions.
#
# Stack under test (brought up via docker-compose by the bring-up
# script in e2e/scripts/bring-up-stack.sh):
#
#   casdoor-systems  — snapdragon.systems Casdoor. Source of truth
#                      for the `game-master` role.
#   casdoor-games    — snapdragon.games Casdoor. Target of the
#                      `admin` role propagation.
#   snapdragon-actions — Spring service running this repo's code.
#                      GameMasterAdminSyncAction runs on a 5 s
#                      interval (override via GM_SYNC_INTERVAL_MS
#                      for the e2e run — shorter than prod's 60 s
#                      so the scenario finishes in seconds).
#
# Reports include per-step PNGs (AfterStep hook), full webm video,
# trace.zip on failure. Output lands in `e2e/results/` after the run.
Feature: GameMasterAdminSyncAction — cross-stack role propagation
  As the platform admin chain
  I expect a `game-master` role grant on snapdragon.systems Casdoor
  to propagate to a matching `admin` role grant on snapdragon.games
  Casdoor within one sync tick
  So one source of truth governs admin authority across both stacks.

  Scenario: granting game-master on systems adds admin on games
    Given the actions service is healthy
    And user "alice" exists on snapdragon-systems Casdoor
    And user "alice" exists on threewizards Casdoor
    And user "alice" has NO admin role on threewizards Casdoor
    When user "alice" is granted game-master on snapdragon-systems Casdoor
    And the sync action ticks once
    Then user "alice" has the admin role on threewizards Casdoor
    And the actions.gm_sync.added counter has incremented

  Scenario: revoking game-master on systems strips admin on games
    Given the actions service is healthy
    And user "bob" exists on snapdragon-systems Casdoor
    And user "bob" exists on threewizards Casdoor
    And user "bob" has the game-master role on snapdragon-systems Casdoor
    And user "bob" has the admin role on threewizards Casdoor
    When user "bob"'s game-master role is removed from snapdragon-systems Casdoor
    And the sync action ticks once
    Then user "bob" has NO admin role on threewizards Casdoor
    And the actions.gm_sync.removed counter has incremented

  Scenario: in-sync state is a no-op
    Given the actions service is healthy
    And the snapdragon-systems game-master set matches the threewizards admin set
    When the sync action ticks once
    Then no Casdoor write was issued on either side
    And the actions.gm_sync.unchanged counter has incremented
