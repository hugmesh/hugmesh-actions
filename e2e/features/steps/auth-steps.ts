import { expect } from '@playwright/test';
import { Given, When, Then } from '../support/fixtures';
import { httpRequest } from './helpers/actions';

const tokensByActor = new Map<string, string>();

async function tokenFor(role: string): Promise<string> {
  const fs = await import('fs');
  const path = await import('path');
  const cwd = process.cwd();
  const tokensFile = path.join(cwd, '.tokens.json');
  if (fs.existsSync(tokensFile)) {
    const parsed = JSON.parse(fs.readFileSync(tokensFile, 'utf8'));
    if (parsed[role]) return parsed[role];
  }
  const envKey = `TOKEN_${role.replace('-', '_').toUpperCase()}`;
  const fromEnv = process.env[envKey];
  if (fromEnv) return fromEnv;
  throw new Error(`No token found for role ${role}; expected $${envKey} or .tokens.json`);
}

Given('{string} is authenticated with role {string}',
      async ({ world }, actor: string, role: string) => {
  const token = await tokenFor(role);
  tokensByActor.set(actor, token);
  world.lastResponseStatus = undefined;
});

When('{string} GETs {string}', async ({ world }, actor: string, path: string) => {
  const token = tokensByActor.get(actor);
  world.lastResponseStatus = await httpRequest('GET', path, { bearerToken: token });
});

When('{string} POSTs to {string}', async ({ world }, actor: string, path: string) => {
  const token = tokensByActor.get(actor);
  world.lastResponseStatus = await httpRequest('POST', path, { bearerToken: token, body: {} });
});

When('{string} POSTs to {string} with empty body',
     async ({ world }, actor: string, path: string) => {
  const token = tokensByActor.get(actor);
  world.lastResponseStatus = await httpRequest('POST', path, { bearerToken: token, body: {} });
});

When('the unauthenticated client GETs {string}', async ({ world }, path: string) => {
  world.lastResponseStatus = await httpRequest('GET', path);
});

When('the unauthenticated client POSTs to {string}', async ({ world }, path: string) => {
  world.lastResponseStatus = await httpRequest('POST', path, { body: {} });
});

Then('the response status is {int}', async ({ world }, expected: number) => {
  expect(world.lastResponseStatus, 'last response status').toBe(expected);
});

Then('the response status is one of {int} or {int}',
     async ({ world }, a: number, b: number) => {
  expect([a, b], 'last response status').toContain(world.lastResponseStatus);
});

Then('the response status is one of {int} or {int} or {int}',
     async ({ world }, a: number, b: number, c: number) => {
  expect([a, b, c], 'last response status').toContain(world.lastResponseStatus);
});
