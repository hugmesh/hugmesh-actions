import { request } from '@playwright/test';
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

const BASE_URL = process.env.ACTIONS_BASE_URL ?? 'http://localhost:8090';

/** The GAME-MASTER bearer bring-up-stack.sh minted into e2e/.tokens.json. */
function metricsToken(): string {
  const path = process.env.E2E_TOKENS_PATH ?? join(__dirname, '..', '..', '..', '.tokens.json');
  const token = existsSync(path) ? JSON.parse(readFileSync(path, 'utf8'))['GAME-MASTER'] : undefined;
  if (!token) throw new Error(`no GAME-MASTER token in ${path}; run e2e/scripts/bring-up-stack.sh`);
  return token;
}

/** Read a single Prometheus counter by name. Returns 0 when the
 *  counter hasn't been emitted yet (Micrometer doesn't write the
 *  row until the first increment). */
export async function readCounter(name: string): Promise<number> {
  const api = await request.newContext();
  try {
    // /actuator/** is hasRole("GAME-MASTER"). Without this token every read
    // was a 401 body, the regex found nothing, and every counter read as 0.
    const r = await api.get(`${BASE_URL}/actuator/prometheus`, {
      headers: { Authorization: `Bearer ${metricsToken()}` },
    });
    if (!r.ok()) throw new Error(`GET /actuator/prometheus: HTTP ${r.status()}`);
    const text = await r.text();
    const metricName = name.replace(/\./g, '_') + '_total';
    const m = text.match(new RegExp(`^${metricName}(?:\\{[^}]*\\})?\\s+([0-9.eE+-]+)`, 'm'));
    return m ? parseFloat(m[1]) : 0;
  } finally {
    await api.dispose();
  }
}

export async function waitForActionsHealthy(timeoutMs = 60_000): Promise<void> {
  const api = await request.newContext();
  const deadline = Date.now() + timeoutMs;
  try {
    while (Date.now() < deadline) {
      try {
        const r = await api.get(`${BASE_URL}/actuator/health`);
        if (r.ok()) return;
      } catch { /* try again */ }
      await new Promise(res => setTimeout(res, 500));
    }
    throw new Error(`actions service not healthy after ${timeoutMs}ms at ${BASE_URL}`);
  } finally {
    await api.dispose();
  }
}

export async function waitForSyncTick(): Promise<void> {
  const interval = parseInt(process.env.GM_SYNC_INTERVAL_MS ?? '2000', 10);
  // Wait 2.2× the interval — guarantees we cross at least one full
  // tick boundary even if the request landed mid-tick.
  await new Promise(res => setTimeout(res, Math.max(2000, interval * 2.2)));
}

export async function httpRequest(
  method: 'GET' | 'POST' | 'PUT' | 'DELETE',
  path: string,
  options: { bearerToken?: string; body?: unknown } = {},
): Promise<number> {
  const api = await request.newContext();
  try {
    const headers: Record<string, string> = { 'Content-Type': 'application/json' };
    if (options.bearerToken) headers.Authorization = `Bearer ${options.bearerToken}`;
    const r = await api.fetch(`${BASE_URL}${path}`, {
      method,
      headers,
      data: options.body == null ? undefined : JSON.stringify(options.body),
    });
    return r.status();
  } finally {
    await api.dispose();
  }
}
