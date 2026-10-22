import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { spawnSync } from 'node:child_process';

const root = fileURLToPath(new URL('../', import.meta.url));
const require = createRequire(new URL('./license-tools/package.json', import.meta.url));
const checker = require('license-checker');
const args = new Set(process.argv.slice(2));
for (const arg of args) {
  if (!['--skip-gradle', '--check'].includes(arg)) throw new Error(`Неизвестный аргумент: ${arg}`);
}
const readJson = async (name) => JSON.parse(await readFile(path.join(root, name), 'utf8'));
const sort = (rows) => rows.sort((a, b) => a.name < b.name ? -1 : a.name > b.name ? 1 : a.version < b.version ? -1 : a.version > b.version ? 1 : 0);

if (!args.has('--skip-gradle')) {
  for (const extra of [[], ['-p', 'build-logic']]) {
    const command = process.platform === 'win32' ? 'cmd.exe' : './gradlew';
    const prefix = process.platform === 'win32' ? ['/d', '/c', 'gradlew.bat'] : [];
    const result = spawnSync(command, [...prefix, ...extra, 'generateLicenseReport', '--no-daemon', '--no-parallel'], {
      cwd: root, stdio: 'inherit',
    });
    if (result.error) throw result.error;
    if (result.status !== 0) process.exit(result.status ?? 1);
  }
}

const backend = new Map();
const markers = await readJson('scripts/license-tools/gradle-markers.json');
for (const [scope, file] of [
  ['backend', 'build/reports/dependency-license/backend-dependencies.json'],
  ['build-logic', 'build-logic/build/reports/dependency-license/build-logic-dependencies.json'],
]) {
  const report = await readJson(file);
  for (const item of report.dependencies) {
    const key = `${item.moduleName}@${item.moduleVersion}`;
    const reported = item.moduleLicenses?.map((x) => x.moduleLicense).filter(Boolean) ?? [];
    const licenses = reported.length ? reported : markers[key]?.licenses ?? [];
    if (!licenses.length) throw new Error(`Нет лицензии Gradle: ${key}`);
    const existing = backend.get(key);
    backend.set(key, {
      name: item.moduleName, version: item.moduleVersion,
      licenses: [...new Set([...(existing?.licenses ?? []), ...licenses])].sort(),
      sources: [...new Set([...(existing?.sources ?? []), ...(item.moduleLicenses ?? []).map((x) => x.moduleLicenseUrl).filter(Boolean), ...(item.moduleUrls ?? []), ...(markers[key] ? [markers[key].source] : [])])].sort(),
      scopes: [...new Set([...(existing?.scopes ?? []), scope])].sort(),
    });
  }
}

const npm = [];
for (const directory of ['frontend', 'e2e', 'contracts/postman', 'scripts/license-tools']) {
  const installed = await new Promise((resolve, reject) => checker.init({
    start: path.join(root, directory), json: true,
  }, (error, data) => error ? reject(error) : resolve(data)));
  const lock = await readJson(`${directory}/package-lock.json`);
  const unique = new Map();
  for (const [location, item] of Object.entries(lock.packages)) {
    if (!location || item.link) continue;
    const name = item.name ?? location.split('node_modules/').at(-1);
    const key = `${name}@${item.version}`;
    const detected = installed[key];
    // Lock metadata also covers optional binaries absent on the current operating system.
    const value = item.license ?? detected?.licenses;
    if (!value || value === 'UNKNOWN' || value === 'UNLICENSED') throw new Error(`Нет лицензии npm: ${directory}: ${key}`);
    const licenses = Array.isArray(value) ? value : [value];
    unique.set(key, {
      name, version: item.version, licenses,
      source: `https://registry.npmjs.org/${name.replaceAll('/', '%2f')}/${item.version}`,
      provenance: item.license ? 'package-lock.json' : 'license-checker: LICENSE/manifest (* = распознано по тексту)',
    });
  }
  if (!Object.keys(installed).some((key) => unique.has(key))) throw new Error(`Не установлен npm-проект: ${directory}; выполните npm ci`);
  npm.push({ scope: directory, dependencies: sort([...unique.values()]) });
}

const components = await readJson('scripts/license-tools/components.json');
const inventory = { backend: sort([...backend.values()]), npm, ...components };
const lines = [
  '# Сторонние компоненты', '',
  'Состав формируется командой `node scripts/generate-notices.mjs`. Версии берутся из',
  'разрешённых Gradle-конфигураций и npm lock-файлов. Включены транзитивные зависимости,',
  'тесты и инструменты сборки. Лицензии Gradle — из POM/артефактов через Gradle License Report;',
  'npm — из lock-файлов, дополненных license-checker для пакетов без поля license.',
  'Знак * у npm означает распознавание лицензии по тексту. Перечень сохраняет upstream-названия',
  'и варианты лицензий; несколько названий не переинтерпретируются как выбранная лицензия.',
  'Два маркера Gradle без license в POM дополнены по исходникам реализации:',
  '`scripts/license-tools/gradle-markers.json` хранит точные версии и основания.',
  'Машиночитаемая версия: `docs/licenses/inventory.json`. Оригинальные LICENSE/NOTICE находятся',
  'в пакетах и по ссылкам ниже; при распространении сохраняйте применимые уведомления.', '',
  '## Бэкенд и сборка Gradle', '', '| Компонент | Версия | Лицензии из метаданных | Источники |',
  '| --- | --- | --- | --- |',
];
const cell = (s) => String(s).replaceAll('|', '\\|').replaceAll('\n', ' ');
for (const item of inventory.backend) {
  lines.push(`| ${item.name} | ${item.version} | ${cell(item.licenses.join('; '))} | ${item.sources.join(' ; ')} |`);
}
for (const group of npm) {
  lines.push('', `## npm: ${group.scope}`, '', '| Пакет | Версия | Лицензия | Источник |', '| --- | --- | --- | --- |');
  for (const item of group.dependencies) lines.push(`| ${item.name} | ${item.version} | ${cell(item.licenses.join('; '))} | ${item.source} |`);
}
lines.push('', '## Docker-образы', '',
  'В таблице указаны лицензии основного ПО и, где отмечено, рецепта образа. Слои ОС и',
  'встроенные библиотеки имеют собственные лицензии: это не объявление одной лицензии',
  'на весь образ. Для точного состава используйте digest развёрнутого образа и его SBOM.',
  'Существующие теги образов перечислены буквально из Compose и Dockerfile.', '',
  '| Образ | Основное ПО | Лицензия | Источник |', '| --- | --- | --- | --- |');
for (const item of components.images) lines.push(`| ${item.image} | ${item.component} | ${item.license} | ${item.source} |`);
lines.push('', '## Шрифт, веса модели и Python-инструменты', '');
for (const item of components.assets) lines.push(`- ${item.name}, ${item.version}: ${item.license}. ${item.source}. ${item.note}`);
lines.push('', '## Смоделированные интеграции', '',
  'Все данные синтетические. Названия описывают роль симулятора и не означают подключение',
  'к реальной организации, наличие её лицензии или использование её данных.', '');
for (const item of components.simulations) lines.push(`- ${item}: HTTP-симулятор в external-sim; синтетические ответы.`);
lines.push('');

await mkdir(path.join(root, 'docs/licenses'), { recursive: true });
for (const [name, text] of [
  ['docs/licenses/inventory.json', `${JSON.stringify(inventory, null, 2)}\n`],
  ['THIRD_PARTY_NOTICES', lines.join('\n')],
]) {
  if (args.has('--check')) {
    if ((await readFile(path.join(root, name), 'utf8')).replaceAll('\r\n', '\n') !== text) {
      throw new Error(`${name} отличается; обновите node scripts/generate-notices.mjs`);
    }
  } else await writeFile(path.join(root, name), text);
}
console.log(JSON.stringify({ gradle: inventory.backend.length, npm: Object.fromEntries(npm.map((g) => [g.scope, g.dependencies.length])), mode: args.has('--check') ? 'check' : 'write' }));
