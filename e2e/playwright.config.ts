import { defineConfig, devices } from '@playwright/test';
import { defineBddConfig } from 'playwright-bdd';

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

  reporter: [
    ['list'],
    ['html', { outputFolder: 'results/html', open: 'never' }],
  ],
  outputDir: 'results/artifacts',
  use: {
    baseURL: process.env.ACTIONS_BASE_URL ?? 'http://localhost:8090',
    ignoreHTTPSErrors: true,
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
