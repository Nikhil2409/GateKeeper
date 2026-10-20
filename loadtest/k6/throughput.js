// 2. Highest stable throughput: the request rate climbs step by step; the run stops by itself once errors pass 1%
//    or p99 passes 500 ms. The last step held is the answer (per-request samples in results/throughput.csv.gz).
//    Also used for the scaling run (1 vs 3 gateway instances, see run.ps1).
import http from 'k6/http';
import { check } from 'k6';
import { GATEWAY, TREND_STATS, auth, pickTenant, provisionTenant, summaryTo } from './lib/tenant.js';

const MAX = Number(__ENV.MAX_RATE || 2000);
const STEP_SECONDS = Number(__ENV.STEP_SECONDS || 30);
const STEPS = 8;

// 8 equal steps up to MAX, each ramped in 5 s and held
const stages = [];
for (let i = 1; i <= STEPS; i++) {
  const target = Math.round((MAX * i) / STEPS);
  stages.push({ target, duration: '5s' }, { target, duration: `${STEP_SECONDS}s` });
}

export const options = {
  summaryTrendStats: TREND_STATS,
  setupTimeout: '240s',
  scenarios: {
    climb: { executor: 'ramping-arrival-rate', startRate: Math.round(MAX / STEPS / 2), timeUnit: '1s', stages, preAllocatedVUs: 100, maxVUs: 1500 },
  },
  thresholds: {
    http_req_failed: [{ threshold: 'rate<0.01', abortOnFail: true, delayAbortEval: '10s' }],
    http_req_duration: [{ threshold: 'p(99)<500', abortOnFail: true, delayAbortEval: '10s' }],
  },
};

export function setup() {
  return provisionTenant();
}

export default function () {
  check(http.get(`${GATEWAY}/${pickTenant()}/fast`, auth(__ITER)), { '200': (r) => r.status === 200 });
}

export const handleSummary = summaryTo(__ENV.RESULT_NAME || 'throughput');
