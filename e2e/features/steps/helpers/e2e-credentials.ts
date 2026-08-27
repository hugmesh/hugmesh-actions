import * as fs from 'fs';
import * as path from 'path';

// =============================================================================
// E2E password resolution — this repo is a CONSUMER, it never invents one.
// =============================================================================
//
// The generator (`e2e_password_ensure()` / `tests/stack/e2e-password.sh`,
// CSPRNG via `openssl rand -base64 24`) lives in hugmesh-generator, not here.
// This repo only resolves what the generator already produced. Precedence:
//
//   1. process.env[`E2E_<PER-USER>_PASSWORD`]  — explicit per-user override,
//      e.g. E2E_ALICE_PASSWORD for username "alice".
//   2. process.env.E2E_PASSWORD                — shared override / same-run
//                                                 propagation.
//   3. the run-state credentials file           — written by whoever
//      generated first (`${E2E_CREDENTIALS_FILE}`, or a `.e2e-credentials`
//      walked up from `process.cwd()`), same two keys.
//   4. THROW, naming `make stack-seed` and the file this repo looked for.
//
// Never a literal fallback: a silent default is the exact bug this removes —
// it lets a misconfigured run authenticate with a guessable password and
// still go green.
// =============================================================================

const CREDENTIALS_FILENAME = '.e2e-credentials';

/** Turn an arbitrary username/role into an env-var-safe upper-snake token,
 *  e.g. "alice" -> "ALICE", "game-master" -> "GAME_MASTER". Mirrors the
 *  TOKEN_<ROLE> convention already used by auth-steps.ts's tokenFor(). */
function envToken(name: string): string {
  return name.replace(/[^a-zA-Z0-9]+/g, '_').toUpperCase();
}

/** Walk up from `startDir` looking for `.e2e-credentials`. Returns the first
 *  match, or null if none is found by the filesystem root. */
function findCredentialsFileUpwards(startDir: string): string | null {
  let dir = path.resolve(startDir);
  for (;;) {
    const candidate = path.join(dir, CREDENTIALS_FILENAME);
    if (fs.existsSync(candidate)) return candidate;
    const parent = path.dirname(dir);
    if (parent === dir) return null; // reached filesystem root
    dir = parent;
  }
}

/** Where the run-state credentials file *should* be: `$E2E_CREDENTIALS_FILE`
 *  if set (existence is not required here — reading tolerates absence),
 *  otherwise the nearest `.e2e-credentials` walking up from cwd. Returns
 *  null only when no override is set AND none is found anywhere above cwd. */
function credentialsFilePath(): string | null {
  const explicit = process.env.E2E_CREDENTIALS_FILE;
  if (explicit) return explicit;
  return findCredentialsFileUpwards(process.cwd());
}

/** Parse plain `KEY=value` lines (no quotes, no `export` keyword — the
 *  format `e2e_password_ensure()` / `make stack-seed` writes, chosen so it
 *  also works with `docker compose --env-file`). A missing or unreadable
 *  file is NOT an error: it returns an empty map so callers fall through to
 *  the next precedence step (or the final throw), never blow up merely
 *  because nothing has been seeded yet. */
function readCredentialsFile(filePath: string): Record<string, string> {
  let raw: string;
  try {
    raw = fs.readFileSync(filePath, 'utf8');
  } catch {
    return {};
  }
  const out: Record<string, string> = {};
  for (const line of raw.split('\n')) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith('#')) continue;
    const eq = trimmed.indexOf('=');
    if (eq === -1) continue;
    out[trimmed.slice(0, eq).trim()] = trimmed.slice(eq + 1).trim();
  }
  return out;
}

/**
 * Resolve the e2e test password for `username` (per-user override key is
 * derived from it), falling back to the shared key when no per-user value
 * resolves. Omit `username` to resolve only the shared password.
 *
 * Never returns a literal default — throws instead, naming `make
 * stack-seed` (the hugmesh-generator target that runs
 * `e2e_password_ensure()` and writes the credentials file) plus the exact
 * path this repo looked for, so the failure is actionable.
 */
export function resolveE2ePassword(username?: string): string {
  const perUserKey = username ? `E2E_${envToken(username)}_PASSWORD` : undefined;

  if (perUserKey && process.env[perUserKey]) return process.env[perUserKey] as string;
  if (process.env.E2E_PASSWORD) return process.env.E2E_PASSWORD;

  const filePath = credentialsFilePath();
  if (filePath) {
    const creds = readCredentialsFile(filePath);
    if (perUserKey && creds[perUserKey]) return creds[perUserKey];
    if (creds.E2E_PASSWORD) return creds.E2E_PASSWORD;
  }

  const lookedAt = filePath ?? path.join(process.cwd(), CREDENTIALS_FILENAME);
  throw new Error(
    `No e2e password resolved${username ? ` for user "${username}"` : ''}. Checked ` +
      `${perUserKey ? `$${perUserKey}, ` : ''}$E2E_PASSWORD, and ${lookedAt}. ` +
      'Run `make stack-seed` to generate and persist one (writes .e2e-credentials), ' +
      'or set $E2E_PASSWORD explicitly.',
  );
}
