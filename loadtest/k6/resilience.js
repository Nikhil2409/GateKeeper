// 5. Steady mixed traffic over the three test tenants (70% GET /fast, 10% /slow, 5% /fail, 15% idempotent POST
//    /orders) while run.ps1 breaks something in the middle:
//    a gateway instance, Kafka, Redis or api-management (stopped at 60 s, started again at 120 s).
//    Per-request samples go to results/<name>.csv.gz; the summary counts each outcome.
import http from 'k6/http';
import { Counter } from 'k6/metrics';
import { GATEWAY, TREND_STATS, auth, pickTenant, provisionTenant, summaryTo } from './lib/tenant.js';

const ok = new Counter('outcome_ok');
const rejected = new Counter('outcome_4xx');
const failed = new Counter('outcome_5xx');
const unreachable = new Counter('outcome_no_response');

export const options = {
  summaryTrendStats: TREND_STATS,
  setupTimeout: '240s',
  scenarios: {
    steady: { executor: 'constant-arrival-rate', rate: Number(__ENV.RATE || 200), timeUnit: '1s', duration: __ENV.DURATION || '180s', preAllocatedVUs: 50, maxVUs: 500 },
  },
  thresholds: {
    'http_req_duration{method:GET}': ['p(99)>=0'],
    'http_req_duration{method:POST}': ['p(99)>=0'],
  },
};

export function setup() {
  return provisionTenant();
}

export default function () {
  const slug = pickTenant();
  const r = Math.random();
  const res = r < 0.85
    ? http.get(`${GATEWAY}/${slug}/${r < 0.7 ? 'fast' : r < 0.8 ? 'slow' : 'fail'}`, auth(__ITER))
    : http.post(`${GATEWAY}/${slug}/orders`, JSON.stringify({ item: 'load' }),
      auth(__ITER, { 'Content-Type': 'application/json', 'Idempotency-Key': `${__VU}-${__ITER}-${Date.now()}` }));
  if (res.status === 0) unreachable.add(1);
  else if (res.status >= 500) failed.add(1);
  else if (res.status >= 400) rejected.add(1);
  else ok.add(1);
}

export const handleSummary = summaryTo(__ENV.RESULT_NAME || 'resilience');
