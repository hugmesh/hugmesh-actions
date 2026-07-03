import { request } from '@playwright/test';

// =============================================================================
// SERVER-SIDE ONLY — Casdoor admin API for test SETUP / provisioning.
// =============================================================================
//
// Everything in this file is server-to-server: it calls Casdoor's admin REST
// API (add-user / get-role / update-role) with the snapdragon-actions client
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
      if (body?.data) return;  // already there

      await api.post(`${this.cfg.baseUrl}/api/add-user`, {
        headers: { Authorization: this.auth(), 'Content-Type': 'application/json' },
        data: {
          owner: this.cfg.organization,
          name: username,
          displayName: username,
          password: 'e2e-pass',
        },
      });
    } finally {
      await api.dispose();
    }
  }

  async getRoleMembers(roleName: string): Promise<string[]> {
    const api = await request.newContext();
    try {
      const url = `${this.cfg.baseUrl}/api/get-role?id=${this.cfg.organization}/${roleName}`;
      const r = await api.get(url, { headers: { Authorization: this.auth() } });
      const body = await r.json().catch(() => ({}));
      const data = body?.data;
      if (data && Array.isArray(data.users)) return data.users as string[];
      return [];
    } finally {
      await api.dispose();
    }
  }

  async setRoleMembers(roleName: string, users: string[]): Promise<void> {
    const api = await request.newContext();
    try {
      const url = `${this.cfg.baseUrl}/api/update-role?id=${this.cfg.organization}/${roleName}`;
      await api.post(url, {
        headers: { Authorization: this.auth(), 'Content-Type': 'application/json' },
        data: { owner: this.cfg.organization, name: roleName, users },
      });
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
      baseUrl:      process.env.CASDOOR_SYSTEMS_URL      ?? 'http://localhost:8001',
      clientId:     process.env.CASDOOR_SYSTEMS_CLIENT_ID     ?? 'snapdragon-actions',
      clientSecret: process.env.CASDOOR_SYSTEMS_CLIENT_SECRET ?? '',
      organization: process.env.CASDOOR_SYSTEMS_ORG     ?? 'snapdragon-systems',
      label: 'systems',
    }),
    games: new CasdoorAdmin({
      baseUrl:      process.env.CASDOOR_GAMES_URL       ?? 'http://localhost:8002',
      clientId:     process.env.CASDOOR_GAMES_CLIENT_ID      ?? 'snapdragon-actions',
      clientSecret: process.env.CASDOOR_GAMES_CLIENT_SECRET  ?? '',
      organization: process.env.CASDOOR_GAMES_ORG       ?? 'threewizards',
      label: 'games',
    }),
  };
}
