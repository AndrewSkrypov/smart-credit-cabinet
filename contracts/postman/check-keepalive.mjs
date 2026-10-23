import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdirSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const directory = fileURLToPath(new URL('.', import.meta.url));
const reports = path.join(directory, 'reports');
mkdirSync(reports, { recursive: true });
let ruleReads = 0;
const server = createServer((request, response) => {
  if (request.url === '/api/v1/rules') ruleReads++;
  response.setHeader('Content-Type', 'application/json');
  // A peer or proxy can close an idle connection before the advertised deadline.
  response.setHeader('Keep-Alive', 'timeout=120');
  response.end(JSON.stringify(request.url === '/api/v1/auth/sign-in' ? { token: 'synthetic-test-token' } : request.url === '/api/v1/bank/products' ? [] : request.url === '/api/v1/rules' ? { version: 1, params: { maxLoad: 0.5 } } : { healthy: true }));
});
server.keepAliveTimeout = 1000;
await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
const fixture = path.join(reports, 'keepalive.postman_collection.json');
writeFileSync(fixture, JSON.stringify({
  info: { name: 'Проверка обёртки с коротким keep-alive', schema: 'https://schema.getpostman.com/json/collection/v2.1.0/collection.json' },
  item: [{ name: 'Пауза дольше keep-alive', request: { method: 'GET', url: '{{baseUrl}}/probe' }, event: [
    { listen: 'prerequest', script: { type: 'text/javascript', exec: ['setTimeout(()=>{},4000);'] } },
    { listen: 'test', script: { type: 'text/javascript', exec: ["pm.test('Синтетический ответ',()=>pm.response.to.have.status(200));"] } }
  ] }]
}, null, 2));
let output = '';
try {
  const status = await new Promise((resolve, reject) => {
    const child = spawn(process.execPath, ['run-newman.mjs', fixture], { cwd: directory, env: { ...process.env, CABINET_URL: `http://127.0.0.1:${server.address().port}`, NEWMAN_REPORT_NAME: 'keepalive' }, stdio: ['ignore', 'pipe', 'pipe'] });
    child.stdout.on('data', (chunk) => { output += chunk; });
    child.stderr.on('data', (chunk) => { output += chunk; });
    child.once('error', reject);
    child.once('close', (code) => resolve(code ?? 1));
  });
  writeFileSync(path.join(reports, 'keepalive.log'), output);
  assert.equal(status, 0, 'После коллекции контрольное чтение должно пережить закрытие keep-alive; проверьте reports/keepalive.log');
  assert.ok(ruleReads >= 2, 'Правила прочитаны до и после дочернего процесса');
  writeFileSync(path.join(reports, 'keepalive-check.json'), JSON.stringify({ passed: true, fixture: 'isolated synthetic HTTP server; not an application integration test', serverKeepAliveMs: 1000, advertisedKeepAliveSeconds: 120, collectionPauseMs: 4000, beforeAndAfterReads: ruleReads }, null, 2) + '\n');
  console.log('Контрольное чтение после долгой коллекции прошло с закрытым прежним keep-alive соединением.');
} finally {
  server.closeAllConnections();
  await new Promise((resolve) => server.close(resolve));
}
