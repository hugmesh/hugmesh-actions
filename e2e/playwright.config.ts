import { defineConfig, devices } from '@playwright/test';
import { defineBddConfig } from 'playwright-bdd';

// playwright-bdd compiles every .feature in features/ into a generated
// .spec.js file under .features-gen/ + wires the World fixture from
// features/support/fixtures.ts. Step defs live under features/steps/.
const bddTestDir = defineBddConfig({
  features: 'features/**/*.feature',
  steps: ['features/steps/**/*.ts', 'features/support/**/*.ts'],
});

export default defineConfig({
  // Single worker — the cross-stack sync test mutates shared Casdoor
  // state on both instances. Parallel runners would race.
  fullyParallel: false,
  workers: 1,
  retries: 1,
  timeout: 240_000,

  // Reports: HTML (Playwright's native report) + per-scenario trace +
  // video + screenshots. Same shape as the snapdragon.games suite so
  // a reviewer's eye doesn't have to context-switch between repos.
  reporter: [
    ['list'],
    ['html', { outputFolder: 'results/html', open: 'never' }],
  ],
  outputDir: 'results/artifacts',
  use: {
    // The snapdragon-actions service exposes /actuator/* + the
    // RegistrationController on this base URL; the bring-up script
    // sets ACTIONS_BASE_URL when launching against the live stack.
    baseURL: process.env.ACTIONS_BASE_URL ?? 'http://localhost:8090',
    ignoreHTTPSErrors: true,
    // Everything ON: per-step PNGs are attached via the AfterStep
    // hook in features/support/hooks.ts; full video + trace covers
    // the gaps between steps (scheduled-tick wait, Casdoor admin
    // API round-trips).
    trace: 'retain-on-failure',
    screenshot: 'on',
    video: 'on',
    actionTimeout: 15_000,
    navigationTimeout: 30_000,
  },
  projects: [
    {
      name: 'bdd',
      testDir: bddTestDir,
      use: { ...devices['Desktop Chrome'] },
    },
  ],
});
