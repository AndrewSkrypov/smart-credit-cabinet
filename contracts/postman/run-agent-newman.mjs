import { createRequire } from 'node:module';
import { mkdir, readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const newman = require('newman');
const address = new URL(process.env.CABINET_URL ?? 'http://127.0.0.1:8080');
if (address.protocol !== 'http:' || !['127.0.0.1', 'localhost'].includes(address.hostname) ||
    address.pathname !== '/' || address.username || address.password || address.search || address.hash) {
  throw new Error('Нужен отдельный локальный синтетический стенд');
}
const base = address.origin + '/api/v1';
const directory = new URL('.', import.meta.url);

async function call(method, route, token, data) {
  const form = data instanceof FormData;
  const response = await fetch(base + route, {
    method,
    headers: {
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(data && !form ? { 'Content-Type': 'application/json' } : {})
    },
    body: form || !data ? data : JSON.stringify(data),
    signal: AbortSignal.timeout(30_000)
  });
  if (!response.ok) throw new Error(`${method} ${route}: HTTP ${response.status}`);
  return response.json();
}

async function signIn(email) {
  const session = await call('POST', '/auth/sign-in', null, { email, password: process.env.DEMO_PASSWORD ?? 'Demo2026' });
  if (session.user.role !== 'AGENT') throw new Error('Для проверки требуются синтетические учётные записи агентов');
  return session.token;
}

const assigned = await signIn('agent@demo.test');
const other = await signIn('agent2@demo.test');
const companies = await call('GET', '/agent/clients', assigned);
const company = companies.find(({ inn }) => inn === '000100000004');
if (!company) throw new Error('Котова отсутствует в тестовом мандате агента');
const input = { companyId: company.companyId, productCode: 'INVESTMENT', purpose: 'EQUIPMENT', amount: 1_000_000, termMonths: 12 };
const draft = await call('POST', '/agent/applications', assigned, input);
try {
  const pdf = await readFile(new URL('fixtures/synthetic.pdf', directory));
  const file = new FormData();
  file.append('file', new Blob([pdf], { type: 'application/pdf' }), 'synthetic.pdf');
  const document = await call('POST', `/agent/applications/${draft.id}/documents?type=TAX_RETURN`, assigned, file);
  const collection = JSON.parse(await readFile(new URL('agent.postman_collection.json', directory), 'utf8'));
  const values = { base, assigned, other, applicationId: draft.id, documentId: document.id, companyId: company.companyId };
  await mkdir(new URL('reports/', directory), { recursive: true });
  const summary = await new Promise((resolve, reject) => {
    newman.run({
      collection, workingDir: fileURLToPath(directory), bail: true,
      environment: { values: Object.entries(values).map(([key, value]) => ({ key, value, enabled: true })) },
      reporters: ['cli', 'junit'], timeoutRequest: 30_000, timeout: 90_000,
      reporter: { junit: { export: fileURLToPath(new URL('reports/newman-agent.xml', directory)) } }
    }, (error, result) => error ? reject(error) : resolve(result));
  });
  if (summary.run.failures.length) process.exitCode = 1;
} finally {
  await call('POST', `/agent/applications/${draft.id}/cancel`, assigned);
}
