import { createRequire } from 'node:module';
import { spawnSync } from 'node:child_process';
import { mkdirSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { isDeepStrictEqual } from 'node:util';
import path from 'node:path';

const directory = fileURLToPath(new URL('.', import.meta.url));
const require = createRequire(import.meta.url);
const base = (process.env.CABINET_URL ?? 'http://localhost:8080').replace(/\/$/, '') + '/api/v1';
const reportName = process.env.NEWMAN_REPORT_NAME ?? 'newman';
if (!/^[a-zA-Z0-9_-]+$/.test(reportName)) throw new Error('Некорректное имя отчёта');
mkdirSync(path.join(directory, 'reports'), { recursive: true });
async function call(method, route, token, data) {
  const response = await fetch(base + route, {
    method,
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: data === undefined ? undefined : JSON.stringify(data),
    signal: AbortSignal.timeout(30_000)
  });
  if (!response.ok) throw new Error(`${method} ${route}: HTTP ${response.status}`);
  return response.json();
}

const session = await call('POST', '/auth/sign-in', null, { email: 'risk@demo.test', password: process.env.DEMO_PASSWORD ?? 'Demo2026' });
const original = await call('GET', '/rules', session.token);
writeFileSync(path.join(directory, `reports/${reportName}-rules-before.json`), JSON.stringify({ version: original.version, params: original.params }, null, 2) + '\n');
let exitCode = 1;
try {
  const collection = process.argv[2] ?? 'cabinet.postman_collection.json';
  const result = spawnSync(process.execPath, [require.resolve('newman/bin/newman.js'), 'run', collection,
    '-e', 'local.postman_environment.json', '--working-dir', '.', '--env-var', `baseUrl=${base}`,
    '--env-var', `demoPassword=${process.env.DEMO_PASSWORD ?? 'Demo2026'}`,
    '--delay-request', '200', '--timeout', '300000', '--timeout-request', '30000', '--timeout-script', '10000',
    '--reporters', 'cli,junit', '--reporter-junit-export', `reports/${reportName}.xml`, '--bail'],
  { cwd: directory, stdio: 'inherit' });
  if (result.error) throw result.error;
  exitCode = result.status ?? 1;
} finally {
  // A failed assertion after the rule write must not leave the next run with a different policy.
  try {
    const current = await call('GET', '/rules', session.token);
    if (!isDeepStrictEqual(current.params, original.params)) {
      const restored = await call('POST', '/rules', session.token, { params: original.params, comment: 'Восстановление параметров после завершения API-проверки' });
      if (!isDeepStrictEqual(restored.params, original.params)) throw new Error('Параметры правил не восстановлены');
      console.log(`Исходные параметры правил восстановлены, версия ${restored.version}.`);
    }
    const after = await call('GET', '/rules', session.token);
    writeFileSync(path.join(directory, `reports/${reportName}-rules-after.json`), JSON.stringify({ version: after.version, params: after.params }, null, 2) + '\n');
  } catch (error) {
    console.error('Не удалось восстановить правила:', error.message);
    exitCode = 1;
  }
}
process.exitCode = exitCode;
