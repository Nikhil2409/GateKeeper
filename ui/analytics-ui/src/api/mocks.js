/**
 * Sample responses for the analytics API until analytic-service exposes it. The real data will come from
 * gateway traffic events on Kafka. Endpoint names are rough and expected to change with the backend:
 *   GET /analytics/summary?range=&org=          headline numbers + deltas vs the previous period
 *   GET /analytics/timeseries?range=&org=       requests / status classes / latency per time bucket
 *   GET /analytics/routes?range=&org=           per-route traffic, errors and latency
 *   GET /analytics/organizations?range=         per-organization traffic (platform admins only)
 *   GET /analytics/requests?range=&org=&status=&q=&page=&size=   the request log
 *   GET /analytics/rate-limits?org=             current-minute usage vs limit per route, 429s in the last hour
 *   GET /analytics/idempotency?org=             idempotency stats and recent outcomes (last hour)
 * Tenant admins are always scoped to their own organization (by the backend, from the JWT).
 */

const ORGANIZATIONS = [
  { id: 1, slug: 'acme', name: 'Acme Corporation', weight: 1 },
  { id: 2, slug: 'globex', name: 'Globex', weight: 0.55 },
  { id: 3, slug: 'initech', name: 'Initech', weight: 0.18 },
];

const ROUTES = {
  acme: [['orders', 'POST', '/orders'], ['payments', 'POST', '/payments'], ['inventory', 'GET', '/inventory/items'],
    ['users', 'GET', '/users/me'], ['search', 'GET', '/search'], ['notifications', 'POST', '/notifications/email']],
  globex: [['catalog', 'GET', '/catalog/products'], ['checkout', 'POST', '/checkout'], ['accounts', 'GET', '/accounts/42'],
    ['reviews', 'GET', '/reviews/latest']],
  initech: [['reports', 'GET', '/reports/q3'], ['tps', 'POST', '/tps/cover-sheet']],
};

const RANGES = {
  '1h': { buckets: 12, stepMinutes: 5 },
  '24h': { buckets: 24, stepMinutes: 60 },
  '7d': { buckets: 28, stepMinutes: 360 },
};

// Small deterministic PRNG so the sample data is stable between reloads
function random(seed) {
  let state = seed >>> 0;
  return () => {
    state = (state * 1664525 + 1013904223) >>> 0;
    return state / 4294967296;
  };
}

const hash = (text) => [...text].reduce((h, c) => (h * 31 + c.charCodeAt(0)) >>> 0, 7);

function scope(orgParam) {
  if (!orgParam) return ORGANIZATIONS;
  const match = ORGANIZATIONS.find((org) => String(org.id) === String(orgParam));
  // A tenant created in your local api-management gets sample data shaped like acme's
  return [match ?? { ...ORGANIZATIONS[0], id: Number(orgParam), weight: 0.6 }];
}

function series(range, orgs, offset = 0) {
  const { buckets, stepMinutes } = RANGES[range];
  const end = Math.floor(Date.now() / (stepMinutes * 60_000)) * stepMinutes * 60_000;
  const weight = orgs.reduce((sum, org) => sum + org.weight, 0);
  const next = random(hash(range + orgs.map((o) => o.slug).join()) + offset);
  const perBucket = { '1h': 9_000, '24h': 105_000, '7d': 560_000 }[range];

  return Array.from({ length: buckets }, (_, i) => {
    const t = end - (buckets - 1 - i + offset * buckets) * stepMinutes * 60_000;
    const hour = new Date(t).getHours() + new Date(t).getMinutes() / 60;
    const daily = 0.55 + 0.45 * Math.sin(((hour - 8) / 24) * 2 * Math.PI) ** 2;
    const incident = range === '24h' && i === buckets - 6 && offset === 0 ? 1 : 0; // one visible error spike
    const requests = Math.round(perBucket * weight * daily * (0.9 + next() * 0.2));
    const s5xx = Math.round(requests * (0.004 + next() * 0.004 + incident * 0.05));
    const rateLimited = Math.round(requests * (0.006 + next() * 0.006));
    const s4xx = Math.round(requests * (0.018 + next() * 0.01)) + rateLimited;
    const p50 = Math.round(38 + next() * 12 + daily * 10 + incident * 40);
    const p95 = Math.round(p50 * (3.1 + next() * 0.6) + incident * 380);
    return {
      t: new Date(t).toISOString(),
      requests, s2xx: requests - s4xx - s5xx, s4xx, s5xx, rateLimited,
      replays: Math.round(requests * 0.0021),
      p50, p95,
      errorRate: (s4xx + s5xx) / requests,
    };
  });
}

function totals(rows) {
  const requests = rows.reduce((s, r) => s + r.requests, 0);
  const sum = (key) => rows.reduce((s, r) => s + r[key], 0);
  const weighted = (key) => Math.round(rows.reduce((s, r) => s + r[key] * r.requests, 0) / requests);
  return {
    requests,
    errorRate: (sum('s4xx') + sum('s5xx')) / requests,
    serverErrorRate: sum('s5xx') / requests,
    p50: weighted('p50'),
    p95: weighted('p95'),
    rateLimited: sum('rateLimited'),
    replays: sum('replays'),
  };
}

const change = (current, previous) => (previous ? (current - previous) / previous : null);

function summary(range, orgs) {
  const current = series(range, orgs);
  const previous = series(range, orgs, 1);
  const now = totals(current);
  const before = totals(previous);
  const trend = (key) => current.map((row) => row[key]);
  return {
    range,
    ...now,
    deltas: {
      requests: change(now.requests, before.requests),
      errorRate: change(now.errorRate, before.errorRate),
      p95: change(now.p95, before.p95),
      rateLimited: change(now.rateLimited, before.rateLimited),
      replays: change(now.replays, before.replays),
    },
    trends: {
      requests: trend('requests'),
      errorRate: trend('errorRate'),
      p95: trend('p95'),
      rateLimited: trend('rateLimited'),
      replays: trend('replays'),
    },
  };
}

function routes(range, orgs) {
  const totalRequests = totals(series(range, orgs)).requests;
  const rows = orgs.flatMap((org) => ROUTES[org.slug].map(([routeId], i, list) => {
    const next = random(hash(org.slug + routeId + range));
    const share = (list.length - i) / ((list.length * (list.length + 1)) / 2);
    const requests = Math.round(totalRequests * (org.weight / orgs.reduce((s, o) => s + o.weight, 0)) * share);
    const p50 = Math.round(25 + next() * 60);
    return {
      organizationSlug: org.slug,
      routeId,
      requests,
      errorRate: 0.01 + next() * (routeId === 'payments' ? 0.08 : 0.03),
      p50,
      p95: Math.round(p50 * (2.8 + next() * 1.5)),
      rateLimited: routeId === 'payments' || routeId === 'reports' ? Math.round(requests * 0.03) : Math.round(requests * next() * 0.004),
      replays: ['orders', 'payments', 'checkout', 'tps'].includes(routeId) ? Math.round(requests * 0.006) : 0,
      trend: Array.from({ length: 12 }, () => Math.round(requests / 12 * (0.75 + next() * 0.5))),
    };
  }));
  return rows.sort((a, b) => b.requests - a.requests);
}

function organizations(range) {
  return ORGANIZATIONS.map((org) => {
    const t = totals(series(range, [org]));
    return { organizationId: org.id, organizationSlug: org.slug, organizationName: org.name, ...t };
  }).sort((a, b) => b.requests - a.requests);
}

const STATUSES = [200, 200, 200, 200, 200, 200, 201, 201, 204, 304, 400, 401, 404, 409, 422, 429, 429, 500, 502, 504];

function requestLog(range, orgs, statusFilter, query, page, size) {
  const next = random(hash('log' + range + orgs.map((o) => o.slug).join()));
  const { stepMinutes, buckets } = RANGES[range];
  const spanMs = stepMinutes * buckets * 60_000;
  const all = Array.from({ length: 240 }, (_, i) => {
    const org = orgs[Math.floor(next() * orgs.length)];
    const [routeId, method, path] = ROUTES[org.slug][Math.floor(next() * ROUTES[org.slug].length)];
    const status = STATUSES[Math.floor(next() * STATUSES.length)];
    return {
      id: `req-${i}`,
      timestamp: new Date(Date.now() - (i / 240) * spanMs - next() * 30_000).toISOString(),
      organizationSlug: org.slug,
      routeId,
      method,
      path: `/${org.slug}${path}`,
      status,
      latencyMs: Math.round(status === 429 ? 1 + next() * 3 : 20 + next() * (status >= 500 ? 2900 : 260)),
      clientIp: `10.${Math.floor(next() * 255)}.${Math.floor(next() * 255)}.${Math.floor(next() * 255)}`,
      idempotentReplay: method === 'POST' && status < 300 && next() < 0.08,
    };
  });
  const term = query.trim().toLowerCase();
  const filtered = all
    .filter((row) => !statusFilter || `${Math.floor(row.status / 100)}xx` === statusFilter)
    .filter((row) => !term || `${row.path} ${row.routeId} ${row.clientIp} ${row.status}`.toLowerCase().includes(term));
  return { total: filtered.length, page, size, items: filtered.slice(page * size, page * size + size) };
}

const minutesAgo = (minutes) => new Date(Date.now() - minutes * 60_000).toISOString();

const RATE_LIMITS = [
  { organizationSlug: 'acme', routeId: 'payments', limitPerMinute: 120, usedThisWindow: 117, rejectedLastHour: 342 },
  { organizationSlug: 'globex', routeId: 'checkout', limitPerMinute: 200, usedThisWindow: 168, rejectedLastHour: 57 },
  { organizationSlug: 'acme', routeId: 'orders', limitPerMinute: 600, usedThisWindow: 402, rejectedLastHour: 0 },
  { organizationSlug: 'initech', routeId: 'reports', limitPerMinute: 30, usedThisWindow: 30, rejectedLastHour: 88 },
  { organizationSlug: 'acme', routeId: 'notifications', limitPerMinute: 60, usedThisWindow: 23, rejectedLastHour: 0 },
  { organizationSlug: 'globex', routeId: 'catalog', limitPerMinute: 900, usedThisWindow: 311, rejectedLastHour: 0 },
  { organizationSlug: 'acme', routeId: 'inventory', limitPerMinute: 1200, usedThisWindow: 288, rejectedLastHour: 0 },
  { organizationSlug: 'globex', routeId: 'reviews', limitPerMinute: 240, usedThisWindow: 41, rejectedLastHour: 0 },
];

const IDEMPOTENCY_EVENTS = [
  { key: '7f3c…a91e', organizationSlug: 'acme', routeId: 'payments', outcome: 'REPLAYED', minutes: 1 },
  { key: '1b20…44d0', organizationSlug: 'globex', routeId: 'checkout', outcome: 'REPLAYED', minutes: 2 },
  { key: 'c9e1…0f7a', organizationSlug: 'acme', routeId: 'orders', outcome: 'IN_PROGRESS_CONFLICT', minutes: 4 },
  { key: '5a7d…be12', organizationSlug: 'acme', routeId: 'payments', outcome: 'REPLAYED', minutes: 6 },
  { key: '90ff…3c88', organizationSlug: 'initech', routeId: 'tps', outcome: 'BODY_MISMATCH', minutes: 11 },
  { key: '2d4e…7701', organizationSlug: 'globex', routeId: 'reviews', outcome: 'REPLAYED', minutes: 15 },
];

function rateLimits(orgs) {
  const slugs = new Set(orgs.map((org) => org.slug));
  return RATE_LIMITS.filter((row) => slugs.has(row.organizationSlug));
}

function idempotency(orgs) {
  const slugs = new Set(orgs.map((org) => org.slug));
  const share = orgs.reduce((sum, org) => sum + org.weight, 0) / ORGANIZATIONS.reduce((sum, org) => sum + org.weight, 0);
  return {
    ttlSeconds: 86_400,
    storedKeys: Math.round(18_432 * share),
    replaysLastHour: Math.round(214 * share),
    inProgressConflictsLastHour: Math.round(9 * share),
    bodyMismatchesLastHour: Math.round(2 * share),
    recent: IDEMPOTENCY_EVENTS.filter((e) => slugs.has(e.organizationSlug)).map(({ minutes, ...e }) => ({ ...e, at: minutesAgo(minutes) })),
  };
}

export async function mockResponse(path) {
  await new Promise((resolve) => setTimeout(resolve, 300));
  const [route, queryString = ''] = path.split('?');
  const params = new URLSearchParams(queryString);
  const range = RANGES[params.get('range')] ? params.get('range') : '24h';
  const orgs = scope(params.get('org'));

  switch (route) {
    case '/analytics/summary': return summary(range, orgs);
    case '/analytics/timeseries': return series(range, orgs);
    case '/analytics/routes': return routes(range, orgs);
    case '/analytics/organizations': return organizations(range);
    case '/analytics/rate-limits': return rateLimits(orgs);
    case '/analytics/idempotency': return idempotency(orgs);
    case '/analytics/requests':
      return requestLog(range, orgs, params.get('status') ?? '', params.get('q') ?? '',
        Number(params.get('page') ?? 0), Number(params.get('size') ?? 25));
    default: throw new Error(`No mock for ${path}`);
  }
}
