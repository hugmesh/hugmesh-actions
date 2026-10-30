import { request } from '@playwright/test';
import { resolveE2ePassword } from './e2e-credentials';

// =============================================================================
// SERVER-SIDE ONLY — Casdoor admin API for test SETUP / provisioning.
// =============================================================================
//
// Everything in this file is server-to-server: it calls Casdoor's admin REST
// API (add-user / get-role / update-role) with the hugmesh-actions client
// credentials to SEED and ASSERT cross-stack role state. This is NOT a login
// and there is no browser here.
//
// This is deliberately allowed to talk to Casdoor directly — it is the
// server-side test-setup carve-out. It never renders Casdoor's hosted login
// page and is never used to log a user in. All BROWSER logins go through OUR
// login-ui native form instead (see helpers/login-ui.ts, POST /bff/login via
// be-gateway).
// =============================================================================

interface CasdoorConfig {
  baseUrl: string;
  clientId: string;
  clientSecret: string;
  organization: string;
  label: string;
}

export class CasdoorAdmin {
  constructor(private cfg: CasdoorConfig) {}

  private auth(): string {
    return 'Basic ' + Buffer.from(`${this.cfg.clientId}:${this.cfg.clientSecret}`).toString('base64');
  }

  /** Ensure a user exists. Idempotent. */
  async ensureUser(username: string): Promise<void> {
    const api = await request.newContext();
    try {
      const url = `${this.cfg.baseUrl}/api/get-user?id=${this.cfg.organization}/${username}`;
      const r = await api.get(url, { headers: { Authorization: this.auth() } });
      const body = await r.json().catch(() => ({}));
      if (body?.status !== 'ok') throw new Error(`get-user ${username}: ${body?.msg}`);
      if (body.data) return;  // already there

      const added = await api.post(`${this.cfg.baseUrl}/api/add-user`, {
        headers: { Authorization: this.auth(), 'Content-Type': 'application/json' },
        data: {
          owner: this.cfg.organization,
          name: username,
          displayName: username,
          // Resolved, never a literal — see helpers/e2e-credentials.ts.
          // $E2E_<USERNAME>_PASSWORD -> $E2E_PASSWORD -> .e2e-credentials -> throw.
          password: resolveE2ePassword(username),
        },
      });
      const addedBody = await added.json().catch(() => ({}));
      if (addedBody?.status !== 'ok') throw new Error(`add-user ${username}: ${addedBody?.msg}`);
    } finally {
      await api.dispose();
    }
  }

  async getRole(roleName: string): Promise<Record<string, unknown> | null> {
    const api = await request.newContext();
    try {
      const url = `${this.cfg.baseUrl}/api/get-role?id=${this.cfg.organization}/${roleName}`;
      const r = await api.get(url, { headers: { Authorization: this.auth() } });
      const body = await r.json().catch(() => ({}));
      if (body?.status !== 'ok') throw new Error(`get-role ${roleName}: ${body?.msg}`);
      return body.data ?? null;
    } finally {
      await api.dispose();
    }
  }

  async getRoleMembers(roleName: string): Promise<string[]> {
    const role = await this.getRole(roleName);
    return role && Array.isArray(role.users) ? (role.users as string[]) : [];
  }

  /** Read-modify-write: Casdoor's update-role writes every column of the
   *  posted object, so a partial body would disable the role. */
  async setRoleMembers(roleName: string, users: string[]): Promise<void> {
    const role = await this.getRole(roleName);
    if (!role) throw new Error(`role ${this.cfg.organization}/${roleName} is not seeded`);
    const api = await request.newContext();
    try {
      const url = `${this.cfg.baseUrl}/api/update-role?id=${this.cfg.organization}/${roleName}`;
      const r = await api.post(url, {
        headers: { Authorization: this.auth(), 'Content-Type': 'application/json' },
        data: { ...role, users },
      });
      const body = await r.json().catch(() => ({}));
      if (body?.status !== 'ok') throw new Error(`update-role ${roleName}: ${body?.msg}`);
    } finally {
      await api.dispose();
    }
  }

  private async getUser(username: string): Promise<Record<string, unknown>> {
    const api = await request.newContext();
    try {
      const url = `${this.cfg.baseUrl}/api/get-user?id=${this.cfg.organization}/${username}`;
      const r = await api.get(url, { headers: { Authorization: this.auth() } });
      const body = await r.json().catch(() => ({}));
      if (body?.status !== 'ok' || !body.data) {
        throw new Error(`get-user ${username}: ${body?.msg || 'no such user'}`);
      }
      return body.data;
    } finally {
      await api.dispose();
    }
  }

  /** The user's stable id: what their tokens carry as `sub`. */
  async getUserId(username: string): Promise<string> {
    return (await this.getUser(username)).id as string;
  }

  /** Write one property. Admin-only on Casdoor's default account items; this
   *  stands in for the real link flow, which is what may write `meshSub`. */
  async setUserProperty(username: string, key: string, value: string): Promise<void> {
    const user = await this.getUser(username);
    const properties = { ...((user.properties as Record<string, string>) ?? {}), [key]: value };
    const api = await request.newContext();
    try {
      const url = `${this.cfg.baseUrl}/api/update-user?id=${this.cfg.organization}/${username}`;
      const r = await api.post(url, {
        headers: { Authorization: this.auth(), 'Content-Type': 'application/json' },
        data: { ...user, properties },
      });
      const body = await r.json().catch(() => ({}));
      if (body?.status !== 'ok') throw new Error(`update-user ${username}: ${body?.msg}`);
    } finally {
      await api.dispose();
    }
  }

  async addRoleMember(roleName: string, username: string): Promise<void> {
    const current = await this.getRoleMembers(roleName);
    const member = `${this.cfg.organization}/${username}`;
    if (current.includes(member)) return;
    await this.setRoleMembers(roleName, [...current, member]);
  }

  async removeRoleMember(roleName: string, username: string): Promise<void> {
    const current = await this.getRoleMembers(roleName);
    const member = `${this.cfg.organization}/${username}`;
    const next = current.filter(u => u !== member);
    if (next.length === current.length) return;
    await this.setRoleMembers(roleName, next);
  }

  memberId(username: string): string {
    return `${this.cfg.organization}/${username}`;
  }
}

/** Build the two Casdoor admins from env vars. Defaults match the
 *  compose stack the bring-up script provisions. */
export function casdoorClients() {
  return {
    systems: new CasdoorAdmin({
      baseUrl:      process.env.CASDOOR_PLATFORM_URL      ?? 'http://localhost:8001',
      clientId:     process.env.CASDOOR_PLATFORM_CLIENT_ID     ?? 'hugmesh-actions',
      clientSecret: process.env.CASDOOR_PLATFORM_CLIENT_SECRET ?? '',
      organization: process.env.CASDOOR_PLATFORM_ORG     ?? 'hugmesh',
      label: 'systems',
    }),
    games: new CasdoorAdmin({
      baseUrl:      process.env.CASDOOR_GAMES_URL       ?? 'http://localhost:8002',
      clientId:     process.env.CASDOOR_GAMES_CLIENT_ID      ?? 'hugmesh-actions',
      clientSecret: process.env.CASDOOR_GAMES_CLIENT_SECRET  ?? '',
      organization: process.env.CASDOOR_GAMES_ORG       ?? 'threewizards',
      label: 'games',
    }),
  };
}
