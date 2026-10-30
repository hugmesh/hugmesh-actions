# Cross-stack admin sync — the headline test for hugmesh-actions.
#
# Stack under test (brought up via docker-compose by the bring-up
# script in e2e/scripts/bring-up-stack.sh, which also seeds both sides:
# lib/casdoor-seed.sh provision_sync_identities):
#
#   casdoor-systems  — hugmesh.net Casdoor. Source of truth
#                      for the `game-master` role.
#   casdoor-games    — hugmesh.games Casdoor. The games realm: its own
#                      `admin` role belongs to it. The sync writes only
#                      `mesh-games-admin`, a role it owns outright.
#   hugmesh-actions — Spring service running this repo's code.
#                      GameMasterAdminSyncAction runs on the interval
#                      compose.yaml sets (2 s by default for this stack,
#                      GM_SYNC_INTERVAL_MS; production is 60 s).
#
# Reports include per-step PNGs (AfterStep hook), full webm video,
# trace.zip on failure. Output lands in `e2e/results/` after the run.
Feature: GameMasterAdminSyncAction — cross-stack role propagation
  As the platform admin chain
  I expect a `game-master` role grant on hugmesh.net Casdoor
  to reach the games account LINKED to that person, within one sync tick,
  without touching the games realm's own admins
  So a mesh grant never hands games authority to a same-named stranger.

  Scenario: granting game-master on systems adds the linked games account
    Given the actions service is healthy
    And user "alice" exists on hugmesh Casdoor
    And user "alice-plays" exists on threewizards Casdoor
    And threewizards user "alice-plays" is linked to hugmesh user "alice"
    And user "alice-plays" is removed from mesh-games-admin on threewizards Casdoor
    When user "alice" is granted game-master on hugmesh Casdoor
    And the sync action ticks once
    Then user "alice-plays" has the mesh-games-admin role on threewizards Casdoor
    And the mesh-games-admin role on threewizards Casdoor is still enabled and intact
    And the actions.gm_sync.added counter has incremented

  Scenario: revoking game-master on systems strips the linked games account
    Given the actions service is healthy
    And user "bob" exists on hugmesh Casdoor
    And user "bob" exists on threewizards Casdoor
    And threewizards user "bob" is linked to hugmesh user "bob"
    And user "bob" has the game-master role on hugmesh Casdoor
    And the sync action ticks once
    And user "bob" has the mesh-games-admin role on threewizards Casdoor
    When user "bob"'s game-master role is removed from hugmesh Casdoor
    And the sync action ticks once
    Then user "bob" has NO mesh-games-admin role on threewizards Casdoor
    And the actions.gm_sync.removed counter has incremented

  Scenario: a same-named games account that is not linked gets nothing
    Given the actions service is healthy
    And user "carol" exists on hugmesh Casdoor
    And user "carol" exists on threewizards Casdoor
    When user "carol" is granted game-master on hugmesh Casdoor
    And the sync action ticks once
    Then user "carol" has NO mesh-games-admin role on threewizards Casdoor
    And the actions.gm_sync.unlinked counter has incremented

  Scenario: a games-native admin survives every tick
    Given the actions service is healthy
    And user "native-admin" exists on threewizards Casdoor
    And user "native-admin" has the games-native admin role on threewizards Casdoor
    When user "dave" exists on hugmesh Casdoor
    And user "dave-plays" exists on threewizards Casdoor
    And threewizards user "dave-plays" is linked to hugmesh user "dave"
    And user "dave" is granted game-master on hugmesh Casdoor
    And the sync action ticks once
    Then user "dave-plays" has the mesh-games-admin role on threewizards Casdoor
    And user "native-admin" still has the games-native admin role on threewizards Casdoor

  Scenario: in-sync state is a no-op
    Given the actions service is healthy
    And the hugmesh game-master set matches the threewizards mesh-games-admin set
    And the sync action ticks once
    When the gm_sync counters are read
    And the sync action ticks once
    Then no Casdoor write was issued on either side
    And the actions.gm_sync.unchanged counter has incremented
