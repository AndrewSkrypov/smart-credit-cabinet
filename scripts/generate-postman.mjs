import { mkdirSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const directory = fileURLToPath(new URL('../contracts/postman/', import.meta.url));
mkdirSync(path.join(directory, 'fixtures'), { recursive: true });
const items = [];
const script = (listen, code) => ({ listen, script: { type: 'text/javascript', exec: code.split('\n') } });
const ref = (name) => `{{${name}}}`;
function add(name, method, endpoint, { token = 'clientToken', data, tests = '', before = '', status = 200, file = false } = {}) {
  const request = {
    method,
    auth: token ? { type: 'bearer', bearer: [{ key: 'token', value: ref(token), type: 'string' }] } : { type: 'noauth' },
    header: file || data === undefined ? [] : [{ key: 'Content-Type', value: 'application/json' }],
    url: `{{baseUrl}}${endpoint}`
  };
  if (file) request.body = { mode: 'formdata', formdata: [{ key: 'file', type: 'file', src: 'fixtures/synthetic.pdf' }] };
  else if (data !== undefined) request.body = { mode: 'raw', raw: typeof data === 'string' ? data : JSON.stringify(data, null, 2), options: { raw: { language: 'json' } } };
  const event = [script('test', `pm.test("HTTP ${status}", () => pm.response.to.have.status(${status}));\n${tests}`)];
  if (before) event.unshift(script('prerequest', before));
  items.push({ name, request, event });
}
function login(name, account, variable, before = '') {
  const serverTime = variable === 'clientToken' ? `\nconst serverDate=pm.response.headers.get('Date');pm.test('Серверное время доступно',()=>pm.expect(Number.isNaN(Date.parse(serverDate))).to.equal(false));pm.collectionVariables.set('runStarted',new Date(serverDate).toISOString());` : '';
  add(name, 'POST', '/auth/sign-in', {
    token: null, data: { email: `${account}@demo.test`, password: '{{demoPassword}}' }, before,
    tests: `const session = pm.response.json();\npm.test("Подписанный токен получен", () => pm.expect(session.token.split('.')).to.have.length(3));\npm.collectionVariables.set("${variable}", session.token);${serverTime}`
  });
}
function cleanup(name, token) {
  add(name, 'GET', '/applications', {
    token,
    tests: `const active = pm.response.json().filter(a => !['DISBURSED','REJECTED','WITHDRAWN','CLOSED'].includes(a.status));
pm.test("Активные демо-заявки сняты", function(done) {
  let index = 0;
  function next() {
    if (index === active.length) return done();
    const app = active[index++];
    pm.sendRequest({url: pm.environment.get('baseUrl') + '/applications/' + app.id + '/withdraw', method:'POST',
      header: {Authorization:'Bearer ' + pm.collectionVariables.get('${token}'), 'Content-Type':'application/json'},
      body:{mode:'raw',raw:JSON.stringify({comment:'Подготовка повторного API-прогона'})}}, (error, response) => {
        if (error) return done(error);
        try { pm.expect(response.code, 'Снятие ' + app.id + ' (' + app.status + ')').to.equal(200); next(); } catch (failure) { done(failure); }
      });
  }
  next();
});`
  });
}
const input = (amount = 1000000, termMonths = 12, declaredRevenue12m = 14400000) => ({ productCode: 'WORKING_CAPITAL', purpose: 'WORKING_CAPITAL', amount, termMonths, declaredRevenue12m, bureauConsent: true });
function create(name, token, body, variable) {
  add(name, 'POST', '/applications', { token, data: body, status: 201, tests: `const app=pm.response.json();\npm.test("Создан новый черновик",()=>pm.expect(app.status).to.equal('DRAFT'));\npm.collectionVariables.set('${variable}',app.id);` });
}
function submit(name, token, variable) {
  add(name, 'POST', `/applications/${ref(variable)}/submit`, {
    token, data: {}, before: `pm.request.headers.upsert({key:'Idempotency-Key',value:'postman-'+pm.collectionVariables.get('${variable}')});`,
    tests: `pm.test("Ответ относится к своей заявке",()=>pm.expect(pm.response.json().id).to.equal(pm.collectionVariables.get('${variable}')));`
  });
}
function poll(name, token, variable, expected) {
  const key = 'deadline_' + items.length;
  add(name, 'GET', `/applications/${ref(variable)}`, {
    token,
    before: `if (!pm.collectionVariables.get('${key}')) pm.collectionVariables.set('${key}',Date.now()+90000);`,
    tests: `const app=pm.response.json();
if (app.status !== '${expected}' && Date.now() < Number(pm.collectionVariables.get('${key}'))) {
  pm.test("Допустимый промежуточный статус",()=>pm.expect(['SUBMITTED','ENRICHMENT','SCORING','DISBURSING']).to.include(app.status));
  pm.execution.setNextRequest(pm.info.requestName);
  setTimeout(()=>{},500);
} else {
  pm.collectionVariables.unset('${key}');
  pm.test("Достигнут ${expected}",()=>pm.expect(app.status).to.equal('${expected}'));
}`
  });
}

login('01. Вход клиента', 'client', 'clientToken');
cleanup('02. Подготовка клиентского сценария', 'clientToken');
add('03. Подбор продукта', 'POST', '/products/match', { data: { purpose: 'WORKING_CAPITAL', amount: 1000000, termMonths: 12 }, tests: "pm.test('Оборотный кредит присутствует',()=>pm.expect(pm.response.json().some(x=>x.product.code==='WORKING_CAPITAL')).to.equal(true));" });
add('04. Расчёт платежа', 'GET', '/products/WORKING_CAPITAL/calculation?amount=1000000&termMonths=12', { tests: "pm.test('График на 12 месяцев',()=>pm.expect(pm.response.json().schedule).to.have.length(12));" });
create('05. Создание черновика', 'clientToken', input(), 'clientApplication');
add('06. Сохранение черновика', 'PUT', `/applications/${ref('clientApplication')}`, { data: { ...input(), purposeComment: 'Синтетическая заявка для проверки контракта' }, tests: "pm.test('Черновик сохранён',()=>pm.expect(pm.response.json().status).to.equal('DRAFT'));" });
add('07. Загрузка документа в черновик', 'POST', `/applications/${ref('clientApplication')}/documents?type=OTHER`, { file: true, status: 201, tests: "pm.test('Документ привязан к заявке',()=>pm.expect(pm.response.json().id).to.be.a('string'));" });
submit('08. Отправка с Idempotency-Key', 'clientToken', 'clientApplication');
submit('09. Повтор той же отправки', 'clientToken', 'clientApplication');
poll('10. Ожидание оффера', 'clientToken', 'clientApplication', 'OFFER_ISSUED');
add('11. Принятие оффера', 'POST', `/applications/${ref('clientApplication')}/offer/accept`, { data: {}, tests: "const app=pm.response.json();pm.test('Выпущен код подписи',()=>pm.expect(app.process.signing.code).to.match(/^\\d{6}$/));pm.collectionVariables.set('signingCode',app.process.signing.code);" });
add('12. Подписание кодом', 'POST', `/applications/${ref('clientApplication')}/sign`, { data: { code: '{{signingCode}}' }, tests: "pm.test('Подпись принята',()=>pm.expect(['DISBURSING','DISBURSED']).to.include(pm.response.json().status));" });
poll('13. Ожидание выдачи', 'clientToken', 'clientApplication', 'DISBURSED');
add('14. График выданного кредита', 'GET', `/applications/${ref('clientApplication')}/schedule`, { tests: "const schedule=pm.response.json().schedule;pm.test('12 платежей и нулевой остаток',()=>{pm.expect(schedule).to.have.length(12);pm.expect(schedule[11].balance).to.equal(0);});" });
add('15. Журнал и идемпотентность', 'GET', `/applications/${ref('clientApplication')}/events`, { tests: "const events=pm.response.json();pm.test('Отправка и выдача записаны по одному разу',()=>{pm.expect(events.filter(e=>e.toStatus==='SUBMITTED')).to.have.length(1);pm.expect(events.filter(e=>e.toStatus==='DISBURSED')).to.have.length(1);});" });

login('16. Вход клиента ручного сценария', 'logistic', 'manualClientToken');
cleanup('17. Подготовка ручных сценариев', 'manualClientToken');
login('18. Вход андеррайтера', 'underwriter', 'underwriterToken');
create('19. Заявка для запроса сведений и одобрения', 'manualClientToken', input(3000000, 24, 15000000), 'manualApplication');
submit('20. Отправка ручной заявки', 'manualClientToken', 'manualApplication');
poll('21. Ожидание ручного рассмотрения', 'manualClientToken', 'manualApplication', 'MANUAL_REVIEW');
add('22. Карточка оценки', 'GET', `/underwriting/applications/${ref('manualApplication')}`, { token: 'underwriterToken', tests: "pm.test('Есть автоматическая оценка',()=>pm.expect(pm.response.json().automaticDecision).to.be.an('object'));" });
add('23. Запрос дополнительного документа', 'POST', `/underwriting/applications/${ref('manualApplication')}/request-info`, { token: 'underwriterToken', data: { items: [{ documentType: 'OTHER' }], comment: 'Нужен дополнительный документ для ручной проверки' } });
add('24. Загрузка запрошенного документа', 'POST', `/applications/${ref('manualApplication')}/documents?type=OTHER`, { token: 'manualClientToken', file: true, status: 201, tests: "pm.test('Файл загружен',()=>pm.expect(pm.response.json().id).to.be.a('string'));" });
add('25. Ответ клиента на запрос', 'POST', `/applications/${ref('manualApplication')}/info-provided`, { token: 'manualClientToken', data: { answer: 'Синтетический документ приложен' } });
poll('26. Повторное ручное рассмотрение', 'manualClientToken', 'manualApplication', 'MANUAL_REVIEW');
add('27. Одобрение андеррайтера', 'POST', `/underwriting/applications/${ref('manualApplication')}/approve`, { token: 'underwriterToken', data: { comment: 'Доходы и документы проверены, условия допустимы' } });
poll('28. Оффер после ручного одобрения', 'manualClientToken', 'manualApplication', 'OFFER_ISSUED');
add('29. Закрытие проверочного оффера клиентом', 'POST', `/applications/${ref('manualApplication')}/offer/decline`, { token: 'manualClientToken', data: { comment: 'Завершение синтетического сценария' }, tests: "pm.test('Заявка закрыта',()=>pm.expect(pm.response.json().status).to.equal('CLOSED'));" });
create('30. Заявка для контрпредложения', 'manualClientToken', input(3000000, 24, 15000000), 'counterApplication');
submit('31. Отправка для контрпредложения', 'manualClientToken', 'counterApplication');
poll('32. Рассмотрение контрпредложения', 'manualClientToken', 'counterApplication', 'MANUAL_REVIEW');
add('33. Другие условия андеррайтера', 'POST', `/underwriting/applications/${ref('counterApplication')}/counter-offer`, { token: 'underwriterToken', data: { amount: 2000000, termMonths: 24, comment: 'Уменьшена сумма после проверки нагрузки' } });
poll('34. Получение контрпредложения', 'manualClientToken', 'counterApplication', 'OFFER_ISSUED');
add('35. Проверка новых условий', 'GET', `/applications/${ref('counterApplication')}`, { token: 'manualClientToken', tests: "pm.test('Клиент видит новые условия',()=>{const offer=pm.response.json().process.offer;pm.expect(offer.kind).to.equal('COUNTER');pm.expect(offer.amount).to.equal(2000000);});" });
add('36. Отзыв проверочной заявки', 'POST', `/applications/${ref('counterApplication')}/withdraw`, { token: 'manualClientToken', data: { comment: 'Завершение проверки контрпредложения' }, tests: "pm.test('Заявка отозвана',()=>pm.expect(pm.response.json().status).to.equal('WITHDRAWN'));" });
create('37. Заявка для ручного отказа', 'manualClientToken', input(3000000, 24, 15000000), 'rejectedApplication');
submit('38. Отправка для ручного отказа', 'manualClientToken', 'rejectedApplication');
poll('39. Ожидание решения специалиста', 'manualClientToken', 'rejectedApplication', 'MANUAL_REVIEW');
add('40. Обоснованный отказ', 'POST', `/underwriting/applications/${ref('rejectedApplication')}/reject`, { token: 'underwriterToken', data: { comment: 'По результатам ручной проверки риск не принят' } });
poll('41. Клиент видит отказ', 'manualClientToken', 'rejectedApplication', 'REJECTED');

login('42. Вход риск-администратора', 'risk', 'riskToken');
add('43. Действующие правила', 'GET', '/rules', { token: 'riskToken', tests: "const rules=pm.response.json();pm.test('Правила имеют версию',()=>pm.expect(rules.version).to.be.a('number'));pm.collectionVariables.set('originalRules',JSON.stringify(rules.params));pm.collectionVariables.set('originalVersion',rules.version);const changed=JSON.parse(JSON.stringify(rules.params));changed.maxLoad-=0.01;pm.collectionVariables.set('changedRules',JSON.stringify(changed));" });
add('44. Новая версия правил', 'POST', '/rules', { token: 'riskToken', status: 201, data: '{"params":{{changedRules}},"comment":"Проверка версии кредитной политики через API"}', tests: "pm.test('Версия увеличилась',()=>pm.expect(pm.response.json().version).to.equal(Number(pm.collectionVariables.get('originalVersion'))+1));" });
add('45. Восстановление параметров', 'POST', '/rules', { token: 'riskToken', status: 201, data: '{"params":{{originalRules}},"comment":"Восстановление параметров после проверки"}', tests: "pm.test('Исходные параметры восстановлены',()=>pm.expect(pm.response.json().params).to.deep.equal(JSON.parse(pm.collectionVariables.get('originalRules'))));" });
add('46. Аналитика реальных заявок', 'GET', '/analytics/funnel?from={{runStarted}}', { token: 'riskToken', tests: "const analytics=pm.response.json();pm.test('Воронка содержит поданные заявки',()=>{pm.expect(analytics.funnel).to.be.an('array').that.is.not.empty;pm.expect(analytics.funnel[0].applications).to.be.at.least(4);analytics.funnel.forEach(x=>pm.expect(x.applications).to.be.at.least(0));});" });

const collection = {
  info: { name: 'Кредитный кабинет — проверка API', schema: 'https://schema.getpostman.com/json/collection/v2.1.0/collection.json', description: 'Только изолированный демо-стенд. Путь до выдачи, документы, идемпотентность, все решения андеррайтера, версии правил и аналитика. Восстановление параметров выполняется в конце успешного прогона.' },
  item: items,
  variable: ['clientToken', 'manualClientToken', 'underwriterToken', 'riskToken'].map((key) => ({ key, value: '', type: 'string' }))
};
const environment = { name: 'Локальный демо-стенд', _postman_variable_scope: 'environment', values: [
  { key: 'baseUrl', value: 'http://localhost:8080/api/v1', enabled: true },
  { key: 'demoPassword', value: 'Demo2026', enabled: true }
] };
writeFileSync(path.join(directory, 'cabinet.postman_collection.json'), JSON.stringify(collection, null, 2) + '\n');
writeFileSync(path.join(directory, 'local.postman_environment.json'), JSON.stringify(environment, null, 2) + '\n');

// A self-contained PDF fixture avoids redistributing case materials or introducing a PDF library.
const pdfStream = 'BT /F1 12 Tf 20 50 Td (Synthetic API test fixture) Tj ET\n';
const objects = [
  '<< /Type /Catalog /Pages 2 0 R >>',
  '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
  '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 100] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>',
  `<< /Length ${Buffer.byteLength(pdfStream)} >>\nstream\n${pdfStream}endstream`,
  '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>'
];
let pdf = '%PDF-1.4\n';
const offsets = [0];
objects.forEach((object, index) => { offsets.push(Buffer.byteLength(pdf)); pdf += `${index + 1} 0 obj\n${object}\nendobj\n`; });
const xref = Buffer.byteLength(pdf);
pdf += `xref\n0 ${objects.length + 1}\n0000000000 65535 f \n`;
pdf += offsets.slice(1).map((offset) => `${String(offset).padStart(10, '0')} 00000 n \n`).join('');
pdf += `trailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`;
writeFileSync(path.join(directory, 'fixtures/synthetic.pdf'), pdf);
console.log(`Создано ${items.length} запросов с проверками и синтетический PDF.`);
