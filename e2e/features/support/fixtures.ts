// playwright-bdd extends Playwright's `test` with a BDD World. We
// publish `test`/`expect` from here so every step file imports
// through one channel. Hooks (Before / AfterStep / After) are
// registered here too — splitting them across files races with
// playwright-bdd's createBdd(test) factory.

import { test as base, createBdd } from 'playwright-bdd';

/**
 * Shared per-scenario state. The cross-stack sync feature needs to
 * thread the actions counter snapshots from a Background step into
 * a Then assertion later in the scenario, so we hang a mutable
 * `World` off the test fixture.
 */
interface World {
  counters: { added: number; removed: number; unchanged: number; errors: number };
  beforeWriteCounts?: { systems: number; games: number };
  lastResponseStatus?: number;
}

export const test = base.extend<{ world: World }>({
  world: async ({}, use) => {
    await use({
      counters: { added: 0, removed: 0, unchanged: 0, errors: 0 },
    });
  },
});

export const { Given, When, Then, Before, BeforeStep, AfterStep, After } = createBdd(test);

// ── Per-step PNG capture ────────────────────────────────────────────
//
// The AfterStep hook attaches a screenshot at every Gherkin step
// transition. The HTML reporter persists path-based attachments
// (body-based ones succeed at attach time but never reach the
// generated report) so we screenshot to `testInfo.outputDir` first
// and attach by path.
let stepCounter = 0;

Before(async () => { stepCounter = 0; });

AfterStep(async ({ page, $step, $testInfo }) => {
  stepCounter += 1;
  try {
    const title = ($step as { title?: string })?.title ?? `step-${stepCounter}`;
    const safe = `${String(stepCounter).padStart(2, '0')}-${title}`
      .replace(/[^a-z0-9_-]+/gi, '-')
      .slice(0, 80);
    const path = $testInfo.outputPath(`${safe}.png`);
    await page.screenshot({ path, fullPage: false });
    await $testInfo.attach(`step ${safe}`, { path, contentType: 'image/png' });
  } catch {
    // Page might be closed mid-step — drop silently.
  }
});
