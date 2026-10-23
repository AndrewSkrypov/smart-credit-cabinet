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
const productCollection = JSON.parse(readFileSync(path.join(directory, 'cabinet.postman_collection.json'), 'utf8'));
const prepareProduct = productCollection.item.find((item) => item.name.startsWith('78.')).event.find((event) => event.listen === 'prerequest');
productCollection.item = productCollection.item.filter((item) => /^(42|76|79)\./.test(item.name));
assert.equal(productCollection.item.length, 3);
productCollection.item[2].event.unshift(prepareProduct);
productCollection.item[2].event.find((event) => event.listen === 'test').script.exec.push(
  'pm.test("Контролируемая ошибка после записи продукта",()=>pm.expect(true).to.equal(false));'
);
const productFixture = path.join(directory, 'reports/product-recovery-intentional.postman_collection.json');
writeFileSync(productFixture, JSON.stringify(productCollection, null, 2) + '\n');
const productResult = spawnSync(process.execPath, ['run-newman.mjs', productFixture], {
  cwd: directory, encoding: 'utf8', env: { ...process.env, NEWMAN_REPORT_NAME: 'product-recovery-intentional' }
});
writeFileSync(path.join(directory, 'reports/product-recovery-intentional.log'), (productResult.stdout ?? '') + (productResult.stderr ?? ''));
assert.equal(productResult.status, 1, 'Ошибка после изменения продукта должна завершить коллекцию с кодом 1');
const productBefore = JSON.parse(readFileSync(path.join(directory, 'reports/product-recovery-intentional-products-before.json'), 'utf8')).find((product) => product.code === 'WORKING_CAPITAL');
const productAfter = JSON.parse(readFileSync(path.join(directory, 'reports/product-recovery-intentional-products-after.json'), 'utf8')).find((product) => product.code === 'WORKING_CAPITAL');
const { version: beforeProductVersion, ...beforeProductParams } = productBefore;
const { version: afterProductVersion, ...afterProductParams } = productAfter;
beforeProductParams.purposes.sort();
afterProductParams.purposes.sort();
assert.equal(afterProductVersion, beforeProductVersion + 2, 'Изменённые условия восстановлены отдельной новой версией');
assert.ok(isDeepStrictEqual(beforeProductParams, afterProductParams), 'Все параметры продукта должны совпасть');
Object.assign(summary, { productBeforeVersion: beforeProductVersion, productAfterVersion: afterProductVersion, productParametersRestored: true });
writeFileSync(path.join(directory, 'reports/recovery-check.json'), JSON.stringify(summary, null, 2) + '\n');
console.log('Контролируемые ошибки Newman воспроизведены; исходные правила и продукт автоматически восстановлены новыми версиями.');
