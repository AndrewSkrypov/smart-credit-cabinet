import http from 'k6/http';
import { check, fail, sleep } from 'k6';
import { Trend, Counter } from 'k6/metrics';

const cohort = JSON.parse(open(__ENV.COHORT || '/work/reports/cohort.json'));
const base = __ENV.BASE_URL || 'http://gateway';
const decisionTime = new Trend('cabinet_decision_ms', true);
const completed = new Counter('cabinet_decisions');
const apiLatency = new Trend('cabinet_api_ms', true);
export const options = {
  scenarios: { companies: { executor: 'per-vu-iterations', vus: cohort.clients.length, iterations: 1, maxDuration: '6m' } },
  thresholds: { checks: ['rate==1'], http_req_failed: ['rate==0'], cabinet_decisions: [`count==${cohort.clients.length}`] },
  summaryTrendStats: ['min', 'med', 'p(95)', 'p(99)', 'max', 'avg'],
};

function call(method, route, token, body, expected = 200) {
  const response = http.request(method, base + route, body === undefined ? null : JSON.stringify(body), {
    headers: {
      Authorization: `Bearer ${token}`, 'Content-Type': 'application/json',
      'Idempotency-Key': `load-${cohort.run}-${__VU}`,
      'X-Request-Id': `load-${cohort.run}-${__VU}`,
    }, timeout: '30s', tags: { name: route.replace(/[0-9a-f]{8}-[0-9a-f-]{27}/g, ':id') },
  });
  apiLatency.add(response.timings.duration);
  if (!check(response, { [`${method}: HTTP ${expected}`]: r => r.status === expected })) fail(`${method} ${route}: ${response.status} ${response.body}`);
  return response.json();
}

export default function () {
  const client = cohort.clients[__VU - 1];
  const route = `/api/v1/applications/${client.id}`;
  const started = Date.now();
  call('POST', `${route}/submit`, client.token);
  let manual = false;
  while (Date.now() - started < 300_000) {
    const app = call('GET', route, client.token);
    if (['OFFER_ISSUED', 'REJECTED'].includes(app.status)) {
      decisionTime.add(Date.now() - started);
      completed.add(1);
      console.log(`RESULT ${JSON.stringify({id: client.id, status: app.status, manual, elapsed_ms: Date.now() - started})}`);
      return;
    }
    if (app.status === 'MANUAL_REVIEW') {
      manual = true;
      call('POST', `/api/v1/underwriting/applications/${client.id}/approve`, cohort.underwriter, {
        comment: 'Синтетическая нагрузочная проверка: сведения и расхождения рассмотрены андеррайтером',
      });
    }
    if (app.status === 'INFO_REQUESTED') fail(`Неожиданный запрос документов: ${client.id}`);
    sleep(0.5);
  }
  fail(`Заявка ${client.id} не дошла до решения за 300 секунд`);
}

export function handleSummary(data) {
  return { [__ENV.SUMMARY || '/work/reports/summary.json']: JSON.stringify(data, null, 2) };
}
