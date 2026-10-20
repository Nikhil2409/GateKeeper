// 1. What the gateway adds: the same request at the same rate, first straight to the backend (WireMock), then
//    through nginx -> gateway (token check, rate limit, quota, routing, traffic event), spread over the three
//    test tenants. Compare p50/p95/p99.
import http from 'k6/http';
import { check } from 'k6';
import { DIRECT, GATEWAY, TREND_STATS, auth, pickTenant, provisionTenant, summaryTo } from './lib/tenant.js';

const RATE = Number(__ENV.RATE || 300);
const DURATION = __ENV.DURATION || '60s';

export const options = {
  summaryTrendStats: TREND_STATS,
  setupTimeout: '240s',
  scenarios: {
    direct: { executor: 'constant-arrival-rate', exec: 'direct', rate: RATE, timeUnit: '1s', duration: DURATION, preAllocatedVUs: 50, maxVUs: 300 },
    // JIT, connection pools and the Kafka producer warm up here; not part of the measured gateway scenario
    warmup: { executor: 'constant-arrival-rate', exec: 'gateway', rate: RATE, timeUnit: '1s', duration: '15s', preAllocatedVUs: 50, maxVUs: 300, startTime: '62s' },
    gateway: { executor: 'constant-arrival-rate', exec: 'gateway', rate: RATE, timeUnit: '1s', duration: DURATION, preAllocatedVUs: 50, maxVUs: 300, startTime: '80s' },
  },
  // listed so the summary shows each scenario separately
  thresholds: {
    'http_req_duration{scenario:direct}': ['p(99)>=0'],
    'http_req_duration{scenario:gateway}': ['p(99)>=0'],
    'http_req_duration{scenario:warmup}': ['p(99)>=0'],
    'http_req_failed{scenario:direct}': ['rate<0.01'],
    'http_req_failed{scenario:gateway}': ['rate<0.01'],
  },
};

export function setup() {
  return provisionTenant();
}

export function direct() {
  check(http.get(`${DIRECT}/fast`), { 'direct 200': (r) => r.status === 200 });
}

export function gateway() {
  check(http.get(`${GATEWAY}/${pickTenant()}/fast`, auth(__ITER)), { 'gateway 200': (r) => r.status === 200 });
}

export const handleSummary = summaryTo('baseline');
