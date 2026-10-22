import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { isDeepStrictEqual } from 'node:util';
import assert from 'node:assert/strict';
import path from 'node:path';

const directory = fileURLToPath(new URL('.', import.meta.url));
const collection = JSON.parse(readFileSync(path.join(directory, 'cabinet.postman_collection.json'), 'utf8'));
collection.item = collection.item.filter((item) => /^(42|43|44)\./.test(item.name));
assert.equal(collection.item.length, 3);
collection.item[2].event.find((event) => event.listen === 'test').script.exec.push(
  'pm.test("Контролируемая ошибка после записи правил",()=>pm.expect(true).to.equal(false));'
);
mkdirSync(path.join(directory, 'reports'), { recursive: true });
const fixture = path.join(directory, 'reports/recovery-intentional.postman_collection.json');
writeFileSync(fixture, JSON.stringify(collection, null, 2) + '\n');
const result = spawnSync(process.execPath, ['run-newman.mjs', fixture], {
  cwd: directory,
  encoding: 'utf8',
  env: { ...process.env, NEWMAN_REPORT_NAME: 'recovery-intentional' }
});
writeFileSync(path.join(directory, 'reports/recovery-intentional.log'), (result.stdout ?? '') + (result.stderr ?? ''));
assert.equal(result.status, 1, 'Контролируемая ошибка должна сделать прогон неуспешным');
const before = JSON.parse(readFileSync(path.join(directory, 'reports/recovery-intentional-rules-before.json'), 'utf8'));
const after = JSON.parse(readFileSync(path.join(directory, 'reports/recovery-intentional-rules-after.json'), 'utf8'));
assert.equal(after.version, before.version + 2, 'Одна версия изменяет параметры, вторая восстанавливает');
assert.ok(isDeepStrictEqual(after.params, before.params), 'Параметры должны полностью совпадать');
const summary = { passed: true, expectedFailureExit: result.status, beforeVersion: before.version, afterVersion: after.version, parametersRestored: true };
writeFileSync(path.join(directory, 'reports/recovery-check.json'), JSON.stringify(summary, null, 2) + '\n');
console.log('Контролируемая ошибка Newman воспроизведена; исходные правила автоматически восстановлены.');
