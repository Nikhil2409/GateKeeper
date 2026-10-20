// 3. Rate-limit accuracy across 3 gateway instances sharing Redis: the "limited" route allows 600 requests per
//    clock minute; this sends twice that (20/s) for exactly 3 whole minutes, spread over 200 users so the per-user
//    quota (60/min) never kicks in. Expected: 3 x 600 = 1800 answered 200, the rest 429 RATE_LIMITED.
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';
import { GATEWAY, LIMITED_RPM, SLUG, TREND_STATS, auth, provisionTenant, summaryTo } from './lib/tenant.js';

const MINUTES = 3;
const allowed = new Counter('limited_allowed');
const rejected = new Counter('limited_rejected_429');
const other = new Counter('limited_other_status');

export const options = {
  summaryTrendStats: TREND_STATS,
  setupTimeout: '300s',
  scenarios: {
    twice_the_limit: { executor: 'constant-arrival-rate', rate: (LIMITED_RPM * 2) / 60, timeUnit: '1s', duration: `${MINUTES}m`, preAllocatedVUs: 20, maxVUs: 100 },
  },
  thresholds: {
    limited_other_status: ['count==0'],
  },
};

// The counters are per clock minute: start on a minute boundary, so the run covers exactly MINUTES windows
export function setup() {
  const data = provisionTenant();
  const msToNextMinute = 60000 - (Date.now() % 60000);
  sleep((msToNextMinute + 200) / 1000);
  return data;
}

export default function () {
  const res = http.get(`${GATEWAY}/${SLUG}/limited`, auth(__ITER));
  if (res.status === 200) allowed.add(1);
  else if (res.status === 429) rejected.add(1);
  else other.add(1);
  check(res, { '200 or 429': (r) => r.status === 200 || r.status === 429 });
}

export function handleSummary(data) {
  const got = data.metrics.limited_allowed ? data.metrics.limited_allowed.values.count : 0;
  const expected = LIMITED_RPM * MINUTES;
  data.rate_limit_accuracy = { expected_allowed: expected, allowed: got, deviation_percent: ((got - expected) / expected) * 100 };
  console.log(`rate-limit accuracy: ${got} allowed of ${expected} expected (${data.rate_limit_accuracy.deviation_percent.toFixed(2)}%)`);
  return summaryTo('rate-limit')(data);
}
