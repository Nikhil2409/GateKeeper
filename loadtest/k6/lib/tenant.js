// Creates (or reuses) three load-test tenants through the product's own APIs, then waits until all three gateways
// serve them. Everything it creates lives only in the running stack's database, never in the product's code or seed data.
import http from 'k6/http';
import { sleep } from 'k6';

export const MGMT = __ENV.MGMT_URL || 'http://api-management-service:8081';
export const GATEWAY = __ENV.GATEWAY_URL || 'http://nginx:8080';
export const DIRECT = __ENV.DIRECT_URL || 'http://wiremock:8080';
// the instances that must serve the orgs before a run starts (the scaling run uses only smart-gateway-1)
export const GATEWAY_INSTANCES = (__ENV.GATEWAY_INSTANCES || 'smart-gateway-1,smart-gateway-2,smart-gateway-3').split(',');

// Share of the traffic each tenant gets, so their dashboards differ. Admins: <slug>-admin / loadtest-pass-123
export const TENANTS = [
  { slug: 'lt-shop', name: 'Load Test Shop', share: 0.5 },
  { slug: 'lt-payments', name: 'Load Test Payments', share: 0.3 },
  { slug: 'lt-media', name: 'Load Test Media', share: 0.2 },
];
// the rate-limit and hot-reload tests use this one only
export const SLUG = TENANTS[0].slug;
const ADMIN_PASSWORD = 'loadtest-pass-123';
export const LIMITED_RPM = 600;

const idp = JSON.parse(open('/idp/tokens.json'));
export const TOKENS = idp.tokens;

// Same routes for every tenant, all backed by WireMock (docker/wiremock): fast and slow (200 ms) succeed, fail
// always answers 500, limited allows 600/min (per user 60, a tenth), orders is an idempotent POST
export const ROUTES = {
  fast: { routeId: 'fast', name: 'Fast endpoint', pathPattern: '/fast', allowedMethods: ['GET'], responseTimeoutMs: 2000 },
  slow: { routeId: 'slow', name: 'Slow endpoint', pathPattern: '/slow', allowedMethods: ['GET'], responseTimeoutMs: 2000 },
  fail: { routeId: 'fail', name: 'Failing endpoint', pathPattern: '/fail', allowedMethods: ['GET'], responseTimeoutMs: 2000 },
  limited: { routeId: 'limited', name: 'Rate limited', pathPattern: '/limited', allowedMethods: ['GET'], requestsPerMinute: LIMITED_RPM, responseTimeoutMs: 2000 },
  orders: { routeId: 'orders', name: 'Place an order', pathPattern: '/orders', allowedMethods: ['POST'], responseTimeoutMs: 3000, idempotencyEnabled: true, allowedHeaders: ['idempotency-key'] },
};

const json = (token) => ({ headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) } });

function call(method, url, body, token, okStatuses = [200, 201, 204]) {
  const res = http.request(method, url, body === undefined ? null : JSON.stringify(body), json(token));
  if (!okStatuses.includes(res.status)) {
    throw new Error(`${method} ${url} -> ${res.status} ${res.body}`);
  }
  return res;
}

export function login(username, password) {
  const res = http.post(`${MGMT}/auth/login`, JSON.stringify({ username, password }), json());
  return res.status === 200 ? res.json('accessToken') : null;
}

export function routeBody(key, extra = {}) {
  return { targetBaseUrl: DIRECT, targetPathPrefix: '', headerRules: [], allowedHeaders: [], ...ROUTES[key], ...extra };
}

// A tenant picked by its traffic share
export function pickTenant() {
  let r = Math.random();
  for (const tenant of TENANTS) {
    if ((r -= tenant.share) < 0) return tenant.slug;
  }
  return TENANTS[0].slug;
}

function provisionOne(platform, organizations, admins, { slug, name }) {
  let org = organizations.find((o) => o.slug === slug);
  if (!org) {
    org = call('POST', `${MGMT}/api/platform/organizations`, { name, slug }, platform).json();
  } else if (org.status !== 'ACTIVE') {
    call('PATCH', `${MGMT}/api/platform/organizations/${org.id}/activate`, undefined, platform);
  }
  const username = `${slug}-admin`;
  const admin = admins.find((a) => a.username === username);
  if (!admin) {
    call('POST', `${MGMT}/api/platform/tenant-admins`, { username, password: ADMIN_PASSWORD, organizationId: org.id }, platform);
  } else {
    call('PATCH', `${MGMT}/api/platform/tenant-admins/${admin.id}/password`, { password: ADMIN_PASSWORD }, platform);
  }
  const tenant = login(username, ADMIN_PASSWORD);
  if (!tenant) throw new Error(`${username} login failed`);

  call('PUT', `${MGMT}/api/tenant/token-settings`,
    { issuer: idp.issuer, jwksUri: 'http://wiremock:8080/loadtest-idp/jwks.json', audience: idp.audience }, tenant);
  const existing = call('GET', `${MGMT}/api/tenant/routes`, undefined, tenant).json().map((r) => r.routeId);
  for (const key of Object.keys(ROUTES)) {
    if (existing.includes(key)) {
      call('PUT', `${MGMT}/api/tenant/routes/${key}`, routeBody(key), tenant);
      call('PATCH', `${MGMT}/api/tenant/routes/${key}/activate`, undefined, tenant, [200, 204]);
    } else {
      call('POST', `${MGMT}/api/tenant/routes`, routeBody(key), tenant);
    }
  }
  return tenant;
}

export function provisionTenant() {
  // api-management may still be starting (or was just restarted by a chaos run)
  let platform = null;
  for (let i = 0; i < 60 && !platform; i++) {
    platform = login(__ENV.PLATFORM_ADMIN_USERNAME || 'platform-admin', __ENV.PLATFORM_ADMIN_PASSWORD || 'admin-pass-123');
    if (!platform) sleep(2);
  }
  if (!platform) throw new Error('platform admin login failed for 2 minutes');
  const organizations = call('GET', `${MGMT}/api/platform/organizations`, undefined, platform).json();
  const admins = call('GET', `${MGMT}/api/platform/tenant-admins`, undefined, platform).json();
  const tenantTokens = {};
  for (const tenant of TENANTS) {
    tenantTokens[tenant.slug] = provisionOne(platform, organizations, admins, tenant);
  }

  waitUntilServed();
  // warm every instance's JWKS cache for every tenant, so the first measured requests don't pay the key download
  for (const { slug } of TENANTS) {
    for (let i = 0; i < 9; i++) http.get(`${GATEWAY}/${slug}/fast`, auth(0));
  }
  // run.ps1 waits for this line before it breaks anything mid-test
  console.log('LOADTEST_STARTED');
  return { tenantToken: tenantTokens[SLUG], tenantTokens };
}

// Version of an org on each instance (0 = not loaded / instance down)
export function gatewayVersions(slug = SLUG) {
  return GATEWAY_INSTANCES.map((instance) => {
    const res = http.get(`http://${instance}:8080/_gateway/status`, { timeout: '2s' });
    if (res.status !== 200) return 0;
    const org = (res.json('organizations') || []).find((o) => o.slug === slug);
    return org && org.status === 'ACTIVE' && org.routes >= Object.keys(ROUTES).length ? org.version : 0;
  });
}

function waitUntilServed() {
  for (let i = 0; i < 60; i++) {
    if (TENANTS.every(({ slug }) => gatewayVersions(slug).every((v) => v > 0)
      && http.get(`${GATEWAY}/${slug}/fast`, auth(0)).status === 200)) {
      return;
    }
    sleep(1);
  }
  throw new Error('the gateways did not start serving the load-test orgs within 60 s');
}

export function auth(index, extraHeaders = {}) {
  return { headers: { Authorization: `Bearer ${TOKENS[index % TOKENS.length]}`, ...extraHeaders } };
}

// One file per run in loadtest/results, plus k6's usual summary on the console
export function summaryTo(name) {
  return (data) => ({
    [`/results/${name}.json`]: JSON.stringify(data, null, 2),
    stdout: textSummary(data),
  });
}

export const TREND_STATS = ['avg', 'med', 'p(95)', 'p(99)', 'max'];

// Trends (ms), counters and rates only; k6's internal metrics (data sent, iterations ...) are left out
function textSummary(data) {
  const hidden = /^(data_|iteration|vus|http_req_(blocked|connecting|tls|sending|waiting|receiving))/;
  const lines = [''];
  for (const [name, metric] of Object.entries(data.metrics)) {
    if (hidden.test(name)) continue;
    const v = metric.values;
    if (metric.type === 'trend') {
      lines.push(`${name}: avg=${v.avg?.toFixed(1)} p50=${v.med?.toFixed(1)} p95=${v['p(95)']?.toFixed(1)} p99=${v['p(99)']?.toFixed(1)} max=${v.max?.toFixed(1)} ms`);
    } else if (metric.type === 'counter') {
      lines.push(`${name}: ${v.count} (${v.rate?.toFixed(1)}/s)`);
    } else if (metric.type === 'rate') {
      lines.push(`${name}: ${(v.rate * 100).toFixed(3)}%`);
    }
  }
  return lines.join('\n') + '\n';
}
