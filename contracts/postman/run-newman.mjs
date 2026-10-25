import { createRequire } from 'node:module';
import { spawn } from 'node:child_process';
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
    headers: { 'Content-Type': 'application/json', Connection: 'close', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: data === undefined ? undefined : JSON.stringify(data),
    signal: AbortSignal.timeout(30_000)
  });
  if (!response.ok) throw new Error(`${method} ${route}: HTTP ${response.status}`);
  return response.json();
}

const session = await call('POST', '/auth/sign-in', null, { email: 'risk@demo.test', password: process.env.DEMO_PASSWORD ?? 'Demo2026' });
const original = await call('GET', '/rules', session.token);
const originalProducts = await call('GET', '/bank/products', session.token);
// Purposes are a set; JSON array order is not a change to lending conditions.
const productParams = ({ version, purposes, ...params }) => ({ ...params, purposes: [...purposes].sort() });
writeFileSync(path.join(directory, `reports/${reportName}-rules-before.json`), JSON.stringify({ version: original.version, params: original.params }, null, 2) + '\n');
writeFileSync(path.join(directory, `reports/${reportName}-products-before.json`), JSON.stringify(originalProducts, null, 2) + '\n');
let exitCode = 1;
try {
  const collection = process.argv[2] ?? 'cabinet.postman_collection.json';
  const arguments_ = [require.resolve('newman/bin/newman.js'), 'run', collection,
    '-e', 'local.postman_environment.json', '--working-dir', '.', '--env-var', `baseUrl=${base}`,
    '--env-var', `demoPassword=${process.env.DEMO_PASSWORD ?? 'Demo2026'}`,
    '--delay-request', '200', '--timeout', '300000', '--timeout-request', '30000', '--timeout-script', '10000',
    '--reporters', 'cli,junit', '--reporter-junit-export', `reports/${reportName}.xml`, '--bail'];
  // Keep the HTTP client's event loop alive while the collection runs, including closed keep-alive sockets.
  exitCode = await new Promise((resolve, reject) => {
    const child = spawn(process.execPath, arguments_, { cwd: directory, stdio: 'inherit' });
    child.once('error', reject);
    child.once('close', (code) => resolve(code ?? 1));
  });
} finally {
  // A failed assertion after the rule write must not leave the next run with a different policy.
  try {
    const current = await call('GET', '/rules', session.token);
    if (!isDeepStrictEqual(current.params, original.params)) {
      const restored = await call('POST', '/rules', session.token, { params: original.params, expectedVersion: current.version, comment: 'Восстановление параметров после завершения API-проверки' });
      if (!isDeepStrictEqual(restored.params, original.params)) throw new Error('Параметры правил не восстановлены');
      console.log(`Исходные параметры правил восстановлены, версия ${restored.version}.`);
    }
    const after = await call('GET', '/rules', session.token);
    writeFileSync(path.join(directory, `reports/${reportName}-rules-after.json`), JSON.stringify({ version: after.version, params: after.params }, null, 2) + '\n');
  } catch (error) {
    console.error('Не удалось восстановить правила:', error.message);
    exitCode = 1;
  }
  try {
    for (const originalProduct of originalProducts) {
      const current = (await call('GET', '/bank/products', session.token)).find((product) => product.code === originalProduct.code);
      if (!current) throw new Error('Не найден исходный продукт');
      if (!isDeepStrictEqual(productParams(current), productParams(originalProduct))) {
        const restored = await call('PUT', `/bank/products/${originalProduct.code}`, session.token, {
          ...originalProduct, expectedVersion: current.version, reason: 'Восстановление условий после завершения API-проверки'
        });
        if (!isDeepStrictEqual(productParams(restored.product), productParams(originalProduct))) throw new Error('Условия продукта не восстановлены');
      }
    }
    const afterProducts = await call('GET', '/bank/products', session.token);
    writeFileSync(path.join(directory, `reports/${reportName}-products-after.json`), JSON.stringify(afterProducts, null, 2) + '\n');
  } catch (error) {
    console.error('Не удалось восстановить продукты:', error.message);
    exitCode = 1;
  }
}
process.exitCode = exitCode;
