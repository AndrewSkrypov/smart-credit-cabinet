import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
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
  const pacedBefore = endpoint.startsWith('/auth/') ? `${before}\nsetTimeout(()=>{},2100);` : before;
  if (pacedBefore) event.unshift(script('prerequest', pacedBefore));
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
add('11.1. Точный сохранённый текст до подписи', 'GET', `/applications/${ref('clientApplication')}/contract`, { tests: "const view=pm.response.json();pm.test('Текст сохранён и связан с сессией',()=>{pm.expect(view.signable).to.equal(true);pm.expect(view.legacySummary).to.equal(false);pm.expect(view.text).to.equal(view.snapshot.text);pm.expect(view.snapshot.sha256).to.match(/^[a-f0-9]{64}$/);});pm.collectionVariables.set('contractTextHash',view.snapshot.sha256);" });
add('12. Подписание кодом', 'POST', `/applications/${ref('clientApplication')}/sign`, { data: { code: '{{signingCode}}' }, tests: "pm.test('Подпись принята',()=>pm.expect(['DISBURSING','DISBURSED']).to.include(pm.response.json().status));" });
poll('13. Ожидание выдачи', 'clientToken', 'clientApplication', 'DISBURSED');
add('14. График выданного кредита', 'GET', `/applications/${ref('clientApplication')}/schedule`, { tests: "const schedule=pm.response.json().schedule;pm.test('12 платежей и нулевой остаток',()=>{pm.expect(schedule).to.have.length(12);pm.expect(schedule[11].balance).to.equal(0);});" });
add('14.1. После выдачи сохранён тот же текст', 'GET', `/applications/${ref('clientApplication')}/contract`, { tests: "const view=pm.response.json();pm.test('Хеш не изменился и факт подписи сохранён',()=>{pm.expect(view.snapshot.sha256).to.equal(pm.collectionVariables.get('contractTextHash'));pm.expect(view.signedAt).to.be.a('string');pm.expect(view.signable).to.equal(false);});" });
add('14.2. Готовность окончательных файлов', 'GET', `/applications/${ref('clientApplication')}/artifacts`, {
  before: "if(!pm.collectionVariables.get('artifactsDeadline'))pm.collectionVariables.set('artifactsDeadline',Date.now()+90000);",
  tests: `const files=pm.response.json();
pm.test('Три окончательных файла без внутренних ключей',()=>{pm.expect(files).to.have.length(3);files.forEach(f=>{pm.expect(f.phase).to.equal('FINAL');pm.expect(f).not.to.have.property('storedName');pm.expect(f).not.to.have.property('storageBackend');});});
if(files.some(f=>f.status!=='READY')&&Date.now()<Number(pm.collectionVariables.get('artifactsDeadline'))){pm.execution.setNextRequest(pm.info.requestName);setTimeout(()=>{},500);}
else{pm.collectionVariables.unset('artifactsDeadline');pm.test('Файлы готовы с размером и SHA-256',()=>files.forEach(f=>{pm.expect(f.status).to.equal('READY');pm.expect(f.sizeBytes).to.be.above(0);pm.expect(f.checksum).to.match(/^[a-f0-9]{64}$/);}));
for(const [kind,key] of [['CONTRACT_PDF','artifactContract'],['SCHEDULE_PDF','artifactSchedulePdf'],['SCHEDULE_XLSX','artifactScheduleXlsx']])pm.collectionVariables.set(key,files.find(f=>f.kind===kind).id);}`
});
for (const [label, variable, mime, signature] of [
  ['14.3. PDF сохранённых условий', 'artifactContract', 'application/pdf', '%PDF-'],
  ['14.4. PDF графика', 'artifactSchedulePdf', 'application/pdf', '%PDF-'],
  ['14.5. XLSX графика', 'artifactScheduleXlsx', 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', 'PK']
]) {
  add(label, 'GET', `/applications/${ref('clientApplication')}/artifacts/${ref(variable)}/download`, {
    tests: `pm.test('Авторизованное скачивание ожидаемого формата',()=>{pm.expect(pm.response.headers.get('Content-Type')).to.equal('${mime}');pm.expect(pm.response.headers.get('Content-Disposition')).to.include('attachment;');pm.expect(pm.response.headers.get('Cache-Control')).to.equal('no-store');pm.expect(pm.response.text().startsWith('${signature}')).to.equal(true);});`
  });
}
add('14.6. Повтор готового файла идемпотентен', 'POST', `/applications/${ref('clientApplication')}/artifacts/${ref('artifactContract')}/retry`, {
  tests: "pm.test('Готовый файл сохранён под тем же ID',()=>{pm.expect(pm.response.json().id).to.equal(pm.collectionVariables.get('artifactContract'));pm.expect(pm.response.json().status).to.equal('READY');});"
});
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
add('44. Новая версия правил', 'POST', '/rules', { token: 'riskToken', status: 201, data: '{"expectedVersion":{{originalVersion}},"params":{{changedRules}},"comment":"Проверка версии кредитной политики через API"}', tests: "pm.test('Версия увеличилась',()=>pm.expect(pm.response.json().version).to.equal(Number(pm.collectionVariables.get('originalVersion'))+1));pm.collectionVariables.set('savedRulesVersion',pm.response.json().version);" });
add('45. Восстановление параметров', 'POST', '/rules', { token: 'riskToken', status: 201, data: '{"expectedVersion":{{savedRulesVersion}},"params":{{originalRules}},"comment":"Восстановление параметров после проверки"}', tests: "pm.test('Исходные параметры восстановлены',()=>pm.expect(pm.response.json().params).to.deep.equal(JSON.parse(pm.collectionVariables.get('originalRules'))));" });
add('46. Аналитика реальных заявок', 'GET', '/analytics/funnel?from={{runStarted}}', { token: 'riskToken', tests: "const analytics=pm.response.json();pm.test('Воронка содержит поданные заявки',()=>{pm.expect(analytics.funnel).to.be.an('array').that.is.not.empty;pm.expect(analytics.funnel[0].applications).to.be.at.least(4);analytics.funnel.forEach(x=>pm.expect(x.applications).to.be.at.least(0));});" });

add('47. Настройки уведомлений', 'GET', '/notifications/settings', { tests: "const value=pm.response.json();pm.test('Настройки принадлежат текущему пользователю',()=>{pm.expect(value.emailEnabled).to.be.a('boolean');pm.expect(value.emailAvailable).to.be.a('boolean');});pm.collectionVariables.set('notificationVersion',value.version);pm.collectionVariables.set('notificationEnabled',value.emailEnabled);" });
add('48. Сохранение настроек без изменения выбора', 'PUT', '/notifications/settings', { data: '{"emailEnabled":{{notificationEnabled}},"version":{{notificationVersion}}}', tests: "pm.test('Версия настроек увеличилась',()=>pm.expect(pm.response.json().version).to.equal(Number(pm.collectionVariables.get('notificationVersion'))+1));" });
add('49. Повтор устаревшего изменения настроек', 'PUT', '/notifications/settings', { status: 409, data: '{"emailEnabled":{{notificationEnabled}},"version":{{notificationVersion}}}', tests: "pm.test('Конфликт описан по-русски',()=>pm.expect(pm.response.json().message).to.include('Настройки уже изменились'));" });

const mutationHeader = "pm.request.headers.upsert({key:'Idempotency-Key',value:pm.variables.replaceIn('{{$guid}}')});";
const invalidLink = '00000000-0000-0000-0000-000000000000.' + 'x'.repeat(43);
add('50. Возможности входа и почты', 'GET', '/auth/options', { token: null, tests: "pm.test('Возможности явно объявлены',()=>{pm.expect(pm.response.json().demoAccounts).to.be.a('boolean');pm.expect(pm.response.json().mailAvailable).to.equal(false);});" });
add('51. Восстановление без почты недоступно', 'POST', '/auth/password-recovery', { token: null, status: 503, data: { email: 'absent@example.test' }, tests: "pm.test('Почта явно выключена',()=>pm.expect(pm.response.json().message).to.include('выключено'));" });
add('52. Несуществующая ссылка восстановления', 'POST', '/auth/password-reset', { token: null, status: 410, data: { token: invalidLink, password: 'Test2026safe' } });
add('53. Несуществующее приглашение', 'POST', '/auth/invitations/inspect', { token: null, status: 410, data: { token: invalidLink } });
add('54. Принятие несуществующего приглашения запрещено', 'POST', '/auth/invitations/accept', { token: null, status: 410, data: { token: invalidLink, fullName: 'Синтетический сотрудник', password: 'Test2026safe', personalDataConsent: true } });
add('55. Отдельная компания для проверки сотрудников', 'POST', '/auth/sign-up', {
  token: null,
  before: "const digits='00'+Array.from({length:7},()=>Math.floor(Math.random()*10)).join('');const check=[2,4,10,3,5,9,4,6,8].reduce((sum,w,i)=>sum+w*Number(digits[i]),0)%11%10;pm.collectionVariables.set('accessInn',digits+check);pm.collectionVariables.set('accessOwnerEmail','owner-'+pm.variables.replaceIn('{{$guid}}')+'@example.test');pm.collectionVariables.set('employeeEmail','employee-'+pm.variables.replaceIn('{{$guid}}')+'@example.test');",
  data: { inn: '{{accessInn}}', email: '{{accessOwnerEmail}}', fullName: 'Синтетический представитель', password: 'Test2026safe', personalDataConsent: true },
  tests: "const session=pm.response.json();pm.collectionVariables.set('accessOwnerToken',session.token);pm.test('Представитель имеет право подписи',()=>pm.expect(session.user.canSign).to.equal(true));"
});
add('56. Список представителей своей компании', 'GET', '/companies/me/employees', { token: 'accessOwnerToken', tests: "pm.test('Один представитель, управление доступно',()=>{const data=pm.response.json();pm.expect(data.members).to.have.length(1);pm.expect(data.canManage).to.equal(true);});" });
add('57. Приглашение без подписи и почты', 'POST', '/companies/me/invitations', {
  token: 'accessOwnerToken', before: "pm.collectionVariables.set('invitationKey',pm.variables.replaceIn('{{$guid}}'));pm.request.headers.upsert({key:'Idempotency-Key',value:pm.collectionVariables.get('invitationKey')});",
  data: { email: '{{employeeEmail}}', canSign: false },
  tests: "const value=pm.response.json();pm.test('Ссылка показана один раз',()=>pm.expect(typeof value.link==='string' && value.link.includes('#token=')).to.equal(true));pm.collectionVariables.set('invitationId',value.id);pm.collectionVariables.set('oldInvitationToken',value.link.split('#token=')[1]);"
});
add('58. Повтор создания не раскрывает ссылку', 'POST', '/companies/me/invitations', { token: 'accessOwnerToken', before: "pm.request.headers.upsert({key:'Idempotency-Key',value:pm.collectionVariables.get('invitationKey')});", data: { email: '{{employeeEmail}}', canSign: false }, tests: "const value=pm.response.json();pm.test('Приглашение прежнее, ссылка скрыта',()=>{pm.expect(value.id).to.equal(pm.collectionVariables.get('invitationId'));pm.expect(value.link).to.equal(null);});" });
add('59. Проверка действующего приглашения', 'POST', '/auth/invitations/inspect', { token: null, data: { token: '{{oldInvitationToken}}' }, tests: "pm.test('Право подписи не выдано',()=>pm.expect(pm.response.json().canSign).to.equal(false));" });
add('60. Повторная отправка заменяет ссылку', 'POST', '/companies/me/invitations/{{invitationId}}/resend', { token: 'accessOwnerToken', before: mutationHeader, data: {}, tests: "const value=pm.response.json();pm.test('Ссылка заменена',()=>pm.expect(value.id!==pm.collectionVariables.get('invitationId') && typeof value.link==='string').to.equal(true));pm.collectionVariables.set('invitationId',value.id);pm.collectionVariables.set('invitationToken',value.link.split('#token=')[1]);" });
add('61. Прежнее приглашение отменено', 'POST', '/auth/invitations/accept', { token: null, status: 410, data: { token: '{{oldInvitationToken}}', fullName: 'Синтетический сотрудник', password: 'Employee2026', personalDataConsent: true } });
add('62. Сотрудник принимает новое приглашение', 'POST', '/auth/invitations/accept', { token: null, data: { token: '{{invitationToken}}', fullName: 'Синтетический сотрудник', password: 'Employee2026', personalDataConsent: true }, tests: "const session=pm.response.json();pm.test('Сотрудник без подписи',()=>pm.expect(session.user.canSign).to.equal(false));pm.collectionVariables.set('employeeId',session.user.id);pm.collectionVariables.set('employeeToken',session.token);" });
add('63. Сотрудник входит своим паролем', 'POST', '/auth/sign-in', { token: null, data: { email: '{{employeeEmail}}', password: 'Employee2026' }, tests: "pm.collectionVariables.set('employeeToken',pm.response.json().token);" });
add('64. Сотрудник видит заявки компании', 'GET', '/applications', { token: 'employeeToken', tests: "pm.test('Список заявок доступен',()=>pm.expect(pm.response.json()).to.be.an('array'));" });
add('65. Сотрудник без подписи не приглашает коллег', 'POST', '/companies/me/invitations', { token: 'employeeToken', before: mutationHeader, status: 403, data: { email: 'denied@example.test', canSign: false } });
add('66. Представитель отзывает доступ сотрудника', 'DELETE', '/companies/me/employees/{{employeeId}}/access', { token: 'accessOwnerToken', before: mutationHeader });
add('67. Отозванная сессия сразу запрещена в credit', 'GET', '/applications', { token: 'employeeToken', status: 401 });
add('68. Смена пароля представителя', 'POST', '/auth/password', { token: 'accessOwnerToken', data: { currentPassword: 'Test2026safe', password: 'Updated2026' } });
add('69. Прежняя сессия запрещена в identity', 'GET', '/users/me', { token: 'accessOwnerToken', status: 401 });
add('70. Вход новым паролем', 'POST', '/auth/sign-in', { token: null, data: { email: '{{accessOwnerEmail}}', password: 'Updated2026' }, tests: "pm.collectionVariables.set('accessOwnerToken',pm.response.json().token);" });
add('71. Выход на всех устройствах', 'POST', '/auth/logout-all', { token: 'accessOwnerToken', data: {} });
add('72. Прежняя сессия запрещена в engagement', 'GET', '/notifications', { token: 'accessOwnerToken', status: 401 });
add('73. Повторный вход после отзыва сессий', 'POST', '/auth/sign-in', { token: null, data: { email: '{{accessOwnerEmail}}', password: 'Updated2026' }, tests: "pm.collectionVariables.set('accessOwnerToken',pm.response.json().token);" });
add('74. Новое приглашение для отмены', 'POST', '/companies/me/invitations', { token: 'accessOwnerToken', before: mutationHeader, data: { email: '{{employeeEmail}}', canSign: false }, tests: "pm.collectionVariables.set('cancelInvitationId',pm.response.json().id);" });
add('75. Отмена неиспользованного приглашения', 'DELETE', '/companies/me/invitations/{{cancelInvitationId}}', { token: 'accessOwnerToken', before: mutationHeader });

add('76. Каталог банка включает выключенные продукты', 'GET', '/bank/products', { token: 'riskToken', tests: "pm.collectionVariables.set('originalProduct',JSON.stringify(pm.response.json().find(p=>p.code==='WORKING_CAPITAL')));pm.test('Версия продукта указана',()=>pm.expect(JSON.parse(pm.collectionVariables.get('originalProduct')).version).to.be.above(0));" });
add('77. История продукта доступна андеррайтеру', 'GET', '/bank/products/WORKING_CAPITAL', { token: 'underwriterToken', tests: "pm.test('История содержит автора, причину и условия',()=>{const v=pm.response.json()[0];pm.expect(v.reason).to.be.a('string');pm.expect(v.createdAt).to.be.a('string');pm.expect(v.product.version).to.be.above(0);});" });
const prepareProductEdit = "const p=JSON.parse(pm.collectionVariables.get('originalProduct'));pm.collectionVariables.set('productEdit',JSON.stringify({...p,expectedVersion:p.version,baseRate:p.baseRate+0.5,reason:'Проверка версии продукта через API'}));";
add('78. Андеррайтер не редактирует продукты', 'PUT', '/bank/products/WORKING_CAPITAL', { token: 'underwriterToken', data: '{{productEdit}}', before: prepareProductEdit, status: 403 });
add('79. Риск-администратор сохраняет новую версию', 'PUT', '/bank/products/WORKING_CAPITAL', { token: 'riskToken', data: '{{productEdit}}', tests: "const p=pm.response.json().product;pm.collectionVariables.set('editedProductVersion',p.version);pm.test('Создана следующая версия',()=>pm.expect(p.version).to.equal(JSON.parse(pm.collectionVariables.get('originalProduct')).version+1));" });
add('80. Устаревшая правка отклонена', 'PUT', '/bank/products/WORKING_CAPITAL', { token: 'riskToken', data: '{{productEdit}}', status: 409 });
add('81. Исходные условия восстановлены новой версией', 'PUT', '/bank/products/WORKING_CAPITAL', { token: 'riskToken', data: '{{productRestore}}', before: "const p=JSON.parse(pm.collectionVariables.get('originalProduct'));pm.collectionVariables.set('productRestore',JSON.stringify({...p,expectedVersion:Number(pm.collectionVariables.get('editedProductVersion')),reason:'Восстановление условий после проверки API'}));", tests: "pm.test('Ставка восстановлена',()=>pm.expect(pm.response.json().product.baseRate).to.equal(JSON.parse(pm.collectionVariables.get('originalProduct')).baseRate));" });

// Keep the accepted account-access/chat checks in the reproducible generator as well.
items.push(...JSON.parse(readFileSync(new URL('./postman-extra-requests.json', import.meta.url), 'utf8')));

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
