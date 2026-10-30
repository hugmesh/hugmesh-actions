import { expect } from '@playwright/test';
import { Given, When, Then } from '../support/fixtures';
import { casdoorClients } from './helpers/casdoor';
import { readCounter, waitForActionsHealthy, waitForSyncTick } from './helpers/actions';

const SYSTEMS_ROLE = 'game-master';
const GAMES_ROLE   = 'mesh-games-admin';
// The games realm's own role. The sync must never read or write it.
const NATIVE_ADMIN_ROLE = 'admin';

Given('the actions service is healthy', async ({}) => {
  await waitForActionsHealthy();
});

Given('user {string} exists on hugmesh Casdoor', async ({}, username: string) => {
  await casdoorClients().systems.ensureUser(username);
});

Given('user {string} exists on threewizards Casdoor', async ({}, username: string) => {
  await casdoorClients().games.ensureUser(username);
});

Given('threewizards user {string} is linked to hugmesh user {string}',
      async ({}, gamesUser: string, systemsUser: string) => {
  const { systems, games } = casdoorClients();
  await games.setUserProperty(gamesUser, 'meshSub', await systems.getUserId(systemsUser));
});

Given('user {string} is removed from mesh-games-admin on threewizards Casdoor', async ({}, username: string) => {
  await casdoorClients().games.removeRoleMember(GAMES_ROLE, username);
});

Given('user {string} has the games-native admin role on threewizards Casdoor',
      async ({}, username: string) => {
  await casdoorClients().games.addRoleMember(NATIVE_ADMIN_ROLE, username);
});

Given('user {string} has the game-master role on hugmesh Casdoor',
      async ({}, username: string) => {
  await casdoorClients().systems.addRoleMember(SYSTEMS_ROLE, username);
});

Given('the hugmesh game-master set matches the threewizards mesh-games-admin set', async ({}) => {
  // Wipe both sides to empty; the equality holds trivially and the
  // sync tick should be a no-op.
  const { systems, games } = casdoorClients();
  await systems.setRoleMembers(SYSTEMS_ROLE, []);
  await games.setRoleMembers(GAMES_ROLE, []);
});

async function snapshotCounters(world: { counters: Record<string, number> }): Promise<void> {
  world.counters.added     = await readCounter('actions.gm_sync.added');
  world.counters.removed   = await readCounter('actions.gm_sync.removed');
  world.counters.unchanged = await readCounter('actions.gm_sync.unchanged');
  world.counters.unlinked  = await readCounter('actions.gm_sync.unlinked');
}

When('the gm_sync counters are read', async ({ world }) => {
  await snapshotCounters(world);
});

When('user {string} is granted game-master on hugmesh Casdoor',
     async ({ world }, username: string) => {
  await snapshotCounters(world);
  await casdoorClients().systems.addRoleMember(SYSTEMS_ROLE, username);
});

When('user {string}\'s game-master role is removed from hugmesh Casdoor',
     async ({ world }, username: string) => {
  await snapshotCounters(world);
  await casdoorClients().systems.removeRoleMember(SYSTEMS_ROLE, username);
});

When('the sync action ticks once', async ({}) => {
  await waitForSyncTick();
});

Then('user {string} has the mesh-games-admin role on threewizards Casdoor', async ({}, username: string) => {
  const games = casdoorClients().games;
  // Re-read with a short retry loop — the games-side write happens
  // inside the action's tick which fires on its own clock.
  for (let i = 0; i < 5; i++) {
    const members = await games.getRoleMembers(GAMES_ROLE);
    if (members.includes(games.memberId(username))) return;
    await new Promise(r => setTimeout(r, 500));
  }
  const members = await games.getRoleMembers(GAMES_ROLE);
  expect(members, `expected ${username} in ${GAMES_ROLE}`).toContain(games.memberId(username));
});

Then('user {string} has NO mesh-games-admin role on threewizards Casdoor', async ({}, username: string) => {
  const games = casdoorClients().games;
  for (let i = 0; i < 5; i++) {
    const members = await games.getRoleMembers(GAMES_ROLE);
    if (!members.includes(games.memberId(username))) return;
    await new Promise(r => setTimeout(r, 500));
  }
  const members = await games.getRoleMembers(GAMES_ROLE);
  expect(members, `expected ${username} stripped from ${GAMES_ROLE}`)
      .not.toContain(games.memberId(username));
});

Then('user {string} still has the games-native admin role on threewizards Casdoor',
     async ({}, username: string) => {
  const games = casdoorClients().games;
  expect(await games.getRoleMembers(NATIVE_ADMIN_ROLE),
      `the sync removed ${username} from the realm's own admin role`).toContain(games.memberId(username));
});

Then('the mesh-games-admin role on threewizards Casdoor is still enabled and intact', async ({}) => {
  // A partial update-role body resets these on Casdoor 3.80.0.
  const role = await casdoorClients().games.getRole(GAMES_ROLE);
  expect(role?.isEnabled, 'isEnabled').toBe(true);
  expect(role?.displayName, 'displayName').toBe(GAMES_ROLE);
});

Then('the actions.gm_sync.unlinked counter has incremented', async ({ world }) => {
  const after = await readCounter('actions.gm_sync.unlinked');
  expect(after, `gm_sync.unlinked: before=${world.counters.unlinked} after=${after}`)
      .toBeGreaterThan(world.counters.unlinked);
});

Then('the actions.gm_sync.added counter has incremented', async ({ world }) => {
  const after = await readCounter('actions.gm_sync.added');
  expect(after, `gm_sync.added: before=${world.counters.added} after=${after}`)
      .toBeGreaterThan(world.counters.added);
});

Then('the actions.gm_sync.removed counter has incremented', async ({ world }) => {
  const after = await readCounter('actions.gm_sync.removed');
  expect(after, `gm_sync.removed: before=${world.counters.removed} after=${after}`)
      .toBeGreaterThan(world.counters.removed);
});

Then('the actions.gm_sync.unchanged counter has incremented', async ({ world }) => {
  const after = await readCounter('actions.gm_sync.unchanged');
  expect(after, `gm_sync.unchanged: before=${world.counters.unchanged} after=${after}`)
      .toBeGreaterThan(world.counters.unchanged);
});

Then('no Casdoor write was issued on either side', async ({ world }) => {
  // Indirect check: gm_sync.unchanged increments only when the
  // action skipped the write. If it landed in the added/removed
  // path instead, those counters would have moved.
  const added    = await readCounter('actions.gm_sync.added');
  const removed  = await readCounter('actions.gm_sync.removed');
  expect(added,   'gm_sync.added must not have moved').toBe(world.counters.added);
  expect(removed, 'gm_sync.removed must not have moved').toBe(world.counters.removed);
});
