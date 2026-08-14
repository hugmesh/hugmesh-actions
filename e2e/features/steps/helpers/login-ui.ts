import { type Page, request, expect } from '@playwright/test';

// =============================================================================
// BROWSER LOGIN — hugmesh-actions e2e
// =============================================================================
//
// POLICY (do not weaken): every BROWSER login in this suite goes through OUR
// OWN login-ui native form — a plain username/password form we render — and
// NEVER through Casdoor's hosted login page. The native form POSTs to
// `/bff/login` (user-bff, reached via be-gateway; permitAll at the BFF
// security layer). user-bff performs the whole OIDC/PKCE handshake with
// Casdoor entirely server-side. The browser never receives a Casdoor URL, an
// authorization code, a `code_verifier`/`state`, or any JWT / access / refresh
// / id token. The ONLY credential that reaches the browser is the opaque,
// httpOnly `bff_<opaque>` session cookie delivered in the Set-Cookie header.
//
// `/bff/login` contract (user-bff, via be-gateway):
//   Request JSON: { username, password, mfaPasscode?, mfaType? }
//   200 success:  { registrationComplete, status:"active", mfaRequired:false }
//                 + Set-Cookie: bff_<opaque> (httpOnly, SameSite=Lax, host-only).
//                 The session token lives ONLY in the Set-Cookie header.
//   200 MFA:      { mfaRequired:true, mfaType:[...] }  (NO cookie, NO session)
//   401:          { error:"invalid_credentials", message:<casdoor msg> }
//   503:          when the SSO surface is dark (app.bff.oidc.enabled=false, or
//                 blank custody key).
//
// SERVER-SIDE-ONLY carve-out: acquiring a Bearer JWT for test SETUP (to
// seed/assert directly against the actions API) is NOT a browser login and is
// allowed to talk to Casdoor's token endpoint directly. Those paths live in
// `casdoor.ts` and `scripts/bring-up-stack.sh` and are labelled SERVER-SIDE
// ONLY. Nothing in this file talks to Casdoor.
//
// login-ui dependency: this helper drives the login-ui native-form testids
//   - `login-username`  (username field)
//   - `login-password`  (password field)
//   - `login-submit`    (submit button)
// login-ui must expose these test ids. The optional MFA passcode field is
// expected under the `login-mfa-passcode` test id (login-ui will add it); the
// MFA path here is guarded on that dependency.
// =============================================================================

/** be-gateway origin — /bff/login is reached THROUGH the gateway, never by
 *  hitting user-bff directly. */
const GATEWAY_BASE_URL = process.env.GATEWAY_BASE_URL ?? 'http://localhost:8080';

/** login-ui origin — our own native login form. */
const LOGIN_UI_URL = process.env.LOGIN_UI_URL ?? 'http://localhost:8081';

/** Path (relative to login-ui origin) that renders the native form. */
const LOGIN_UI_PATH = process.env.LOGIN_UI_PATH ?? '/login';

export interface LoginCredentials {
  username: string;
  password: string;
  /** Only supply for the MFA-challenge follow-up. */
  mfaPasscode?: string;
  mfaType?: string;
}

const TESTID = {
  username: 'login-username',
  password: 'login-password',
  submit: 'login-submit',
  // login-ui dependency: MFA passcode field. Referenced only on the MFA path.
  mfaPasscode: 'login-mfa-passcode',
} as const;

/**
 * Log in through OUR login-ui native form in a real browser.
 *
 * Navigates to the login-ui form, fills the username/password (and, when the
 * scenario supplies one, the MFA passcode) fields by their login-ui test ids,
 * and submits. Submission POSTs `/bff/login` via be-gateway; on success the
 * browser is left holding the httpOnly `bff_<opaque>` session cookie. Casdoor's
 * hosted form is never rendered or touched.
 *
 * @returns the login-ui page origin the browser landed on after submit.
 */
export async function loginViaNativeForm(page: Page, creds: LoginCredentials): Promise<void> {
  await page.goto(`${LOGIN_UI_URL}${LOGIN_UI_PATH}`);

  // Guard: assert we are on OUR form, not a Casdoor hosted page. If a redirect
  // to Casdoor ever leaks in, this fails loudly instead of silently logging in
  // through the wrong surface.
  await expect(
    page.getByTestId(TESTID.username),
    'expected OUR login-ui native username field (login-ui testid); a Casdoor ' +
      'hosted form would not carry this testid',
  ).toBeVisible();

  await page.getByTestId(TESTID.username).fill(creds.username);
  await page.getByTestId(TESTID.password).fill(creds.password);

  if (creds.mfaPasscode) {
    // login-ui dependency: `login-mfa-passcode` field (login-ui will add it).
    await page.getByTestId(TESTID.mfaPasscode).fill(creds.mfaPasscode);
  }

  await page.getByTestId(TESTID.submit).click();
}

export interface BffLoginResult {
  status: number;
  /** Parsed JSON body. The session token is NEVER in here — only in Set-Cookie. */
  body: Record<string, unknown>;
  /** Raw Set-Cookie header value(s); the `bff_<opaque>` cookie lands here on 200. */
  setCookie: string | null;
}

/**
 * Headless equivalent of the native-form submit: POST `/bff/login` through
 * be-gateway directly. This still exercises OUR contract endpoint (never
 * Casdoor's hosted form), so it is a legitimate browser-login stand-in for
 * asserting the `/bff/login` response contract (200 + Set-Cookie / 200 MFA /
 * 401 / 503) without spinning a full browser.
 *
 * The returned cookie is opaque; the browser (and this helper) never sees a
 * JWT, code, code_verifier, state, or Casdoor URL.
 */
export async function bffLogin(creds: LoginCredentials): Promise<BffLoginResult> {
  const api = await request.newContext();
  try {
    const payload: Record<string, unknown> = {
      username: creds.username,
      password: creds.password,
    };
    if (creds.mfaPasscode) payload.mfaPasscode = creds.mfaPasscode;
    if (creds.mfaType) payload.mfaType = creds.mfaType;

    const r = await api.post(`${GATEWAY_BASE_URL}/bff/login`, {
      headers: { 'Content-Type': 'application/json' },
      data: JSON.stringify(payload),
    });
    const body = (await r.json().catch(() => ({}))) as Record<string, unknown>;
    return {
      status: r.status(),
      body,
      setCookie: r.headers()['set-cookie'] ?? null,
    };
  } finally {
    await api.dispose();
  }
}
