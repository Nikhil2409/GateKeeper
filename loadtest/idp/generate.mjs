// The load-test tenant's identity provider (test only, never part of the product).
//   /idp/private.pem                    RSA key, generated once
//   /idp/tokens.json                    USERS user tokens (sub user-1..N), valid for 24 h, minted on every start
//   /wiremock-files/loadtest-idp/jwks.json   the public key, served by WireMock
//   /wiremock-files/limited             static body for the rate-limited test route
import { createPrivateKey, createPublicKey, generateKeyPairSync, sign } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';

const ISSUER = 'http://wiremock:8080/loadtest-idp';
const AUDIENCE = 'loadtest-api';
const KID = 'loadtest-idp-1';
const USERS = 200;

const keyFile = '/idp/private.pem';
if (!existsSync(keyFile)) {
  const { privateKey } = generateKeyPairSync('rsa', { modulusLength: 2048 });
  writeFileSync(keyFile, privateKey.export({ type: 'pkcs8', format: 'pem' }), { mode: 0o600 });
}
const privateKey = createPrivateKey(readFileSync(keyFile));
const jwk = { ...createPublicKey(privateKey).export({ format: 'jwk' }), kid: KID, alg: 'RS256', use: 'sig' };

mkdirSync('/wiremock-files/loadtest-idp', { recursive: true });
writeFileSync('/wiremock-files/loadtest-idp/jwks.json', JSON.stringify({ keys: [jwk] }));
writeFileSync('/wiremock-files/limited', JSON.stringify({ ok: true }));

const base64url = (value) => Buffer.from(typeof value === 'string' ? value : JSON.stringify(value)).toString('base64url');
const header = base64url({ alg: 'RS256', typ: 'JWT', kid: KID });
const now = Math.floor(Date.now() / 1000);
const tokens = [];
for (let i = 1; i <= USERS; i++) {
  const payload = base64url({ iss: ISSUER, aud: AUDIENCE, sub: `user-${i}`, iat: now, exp: now + 24 * 3600 });
  const signature = sign('RSA-SHA256', Buffer.from(`${header}.${payload}`), privateKey).toString('base64url');
  tokens.push(`${header}.${payload}.${signature}`);
}
writeFileSync('/idp/tokens.json', JSON.stringify({ issuer: ISSUER, audience: AUDIENCE, tokens }));
console.log(`load-test IdP ready: ${USERS} user tokens`);
