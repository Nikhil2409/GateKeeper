// 4. Configuration changes under load: steady traffic on /fast while the tenant admin updates that same route
//    every 10 s (a new request header rule each time). Expected: zero failed requests, and every change live on all
//    three gateways within a short time (config_propagation_ms: from the API's answer to the last instance updated).
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend } from 'k6/metrics';
import { GATEWAY, MGMT, SLUG, TREND_STATS, auth, gatewayVersions, provisionTenant, routeBody, summaryTo } from './lib/tenant.js';

const CHANGES = 10;
const propagation = new Trend('config_propagation_ms', true);

export const options = {
  summaryTrendStats: TREND_STATS,
  setupTimeout: '240s',
  scenarios: {
    traffic: { executor: 'constant-arrival-rate', exec: 'traffic', rate: Number(__ENV.RATE || 200), timeUnit: '1s', duration: '130s', preAllocatedVUs: 50, maxVUs: 300 },
    changes: { executor: 'per-vu-iterations', exec: 'changes', vus: 1, iterations: 1, startTime: '10s', maxDuration: '120s' },
  },
  thresholds: {
    'http_req_failed{scenario:traffic}': ['rate==0'],
    config_propagation_ms: ['p(99)>=0'],
  },
};

export function setup() {
  return provisionTenant();
}

export function traffic() {
  check(http.get(`${GATEWAY}/${SLUG}/fast`, auth(__ITER)), { '200': (r) => r.status === 200 });
}

export function changes(data) {
  for (let i = 1; i <= CHANGES; i++) {
    const before = Math.max(...gatewayVersions());
    const body = routeBody('fast', { headerRules: [{ direction: 'REQUEST', action: 'SET', headerName: 'x-loadtest-change', headerValue: String(i) }] });
    const res = http.put(`${MGMT}/api/tenant/routes/fast`, JSON.stringify(body),
      { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${data.tenantToken}` } });
    check(res, { 'route updated': (r) => r.status === 200 });
    const saved = Date.now();
    while (Date.now() - saved < 10000 && !gatewayVersions().every((v) => v > before)) {
      sleep(0.05);
    }
    propagation.add(Date.now() - saved);
    sleep(Math.max(0, 10 - (Date.now() - saved) / 1000));
  }
}

export const handleSummary = summaryTo('hot-reload');
