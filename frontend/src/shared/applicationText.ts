import { Application, ApplicationStatus, CloseReason, JournalEntry } from '../types';

/** The path as the client sees it; internal steps are folded into these stages. */
export const stages = ['Заявка', 'Проверка данных', 'Сведения о компании', 'Оценка', 'Решение', 'Подписание', 'Выдача'] as const;

const stageOf: Record<ApplicationStatus, number> = {
  DRAFT: 0,
  SUBMITTED: 1,
  INFO_REQUESTED: 1,
  ENRICHMENT: 2,
  SCORING: 3,
  MANUAL_REVIEW: 3,
  OFFER_ISSUED: 4,
  SIGNING: 5,
  DISBURSING: 6,
  DISBURSED: 7,
  REJECTED: 4,
  WITHDRAWN: -1,
  CLOSED: -1
};

/** Index of the current stage; 7 means all done, -1 that the path was left. */
export const stageIndex = (status: ApplicationStatus) => stageOf[status];

/** Statuses in which the system is working: the card refreshes itself often. */
export const SYSTEM_STATUSES: ApplicationStatus[] = ['SUBMITTED', 'ENRICHMENT', 'SCORING', 'DISBURSING'];

export const FINISHED_STATUSES: ApplicationStatus[] = ['DISBURSED', 'REJECTED', 'WITHDRAWN', 'CLOSED'];

const closeReasons: Record<CloseReason, string> = {
  INFO_EXPIRED: 'Заявка закрыта: банк не получил данные в срок',
  OFFER_DECLINED: 'Вы отказались от предложения банка',
  OFFER_EXPIRED: 'Заявка закрыта: срок предложения истёк'
};

const titles: Record<ApplicationStatus, string> = {
  DRAFT: 'Черновик',
  SUBMITTED: 'Заявка принята',
  INFO_REQUESTED: 'Нужны дополнительные данные',
  ENRICHMENT: 'Запрашиваем сведения',
  SCORING: 'Оцениваем заявку',
  MANUAL_REVIEW: 'Рассматривает специалист',
  OFFER_ISSUED: 'Есть решение',
  SIGNING: 'Подпишите договор',
  DISBURSING: 'Зачисляем средства',
  DISBURSED: 'Деньги на счёте',
  REJECTED: 'Отказ',
  WITHDRAWN: 'Отозвана',
  CLOSED: 'Закрыта'
};

/** Short label for lists and chips. */
export const statusTitle = (status: ApplicationStatus) => titles[status];

export type StatusTone = 'default' | 'info' | 'warning' | 'success' | 'error';

/** Whether the status needs the client (warning), is good news (success) or bad (error). */
export function statusTone(status: ApplicationStatus): StatusTone {
  if (status === 'INFO_REQUESTED' || status === 'OFFER_ISSUED' || status === 'SIGNING' || status === 'DRAFT') return 'warning';
  if (status === 'DISBURSED') return 'success';
  if (status === 'REJECTED') return 'error';
  if (status === 'WITHDRAWN' || status === 'CLOSED') return 'default';
  return 'info';
}

export interface StatusExplanation {
  /** What is happening now, in plain words. */
  now: string;
  /** What the client has to do; null when nothing. */
  action: string | null;
}

/** The two questions a client has about an application: what is going on, and is it my turn. */
export function explainStatus(application: Application): StatusExplanation {
  const offer = application.process.offer;
  switch (application.status) {
    case 'DRAFT':
      return { now: 'Заявка ещё не отправлена в банк.', action: 'Заполните данные о бизнесе, загрузите документы и отправьте заявку.' };
    case 'SUBMITTED':
      return { now: 'Банк получил заявку и проверяет, всё ли на месте. Обычно это несколько секунд.', action: null };
    case 'INFO_REQUESTED':
      return { now: 'Для рассмотрения банку не хватает данных.', action: 'Загрузите документы из списка ниже и нажмите «Отправить». Без ответа заявка закроется в срок, указанный в запросе.' };
    case 'ENRICHMENT':
      return { now: 'Запрашиваем сведения о компании в ЕГРЮЛ/ЕГРИП, ФНС и бюро кредитных историй. Если источник отвечает медленно, банк повторит запрос сам.', action: null };
    case 'SCORING':
      return { now: 'Оцениваем заявку по данным источников. Обычно это несколько секунд.', action: null };
    case 'MANUAL_REVIEW':
      return { now: 'Заявку рассматривает специалист банка — обычно в течение рабочего дня. Если понадобятся документы, здесь появится запрос.', action: null };
    case 'OFFER_ISSUED':
      return offer?.kind === 'COUNTER'
        ? { now: 'Банк готов выдать кредит на других условиях.', action: 'Сравните условия с запрошенными и примите или отклоните предложение.' }
        : { now: 'Банк одобрил заявку на запрошенных условиях.', action: 'Проверьте условия и примите предложение, чтобы перейти к подписанию.' };
    case 'SIGNING':
      return { now: 'Договор подготовлен.', action: 'Подпишите договор одноразовым кодом.' };
    case 'DISBURSING':
      return { now: 'Договор подписан, зачисляем деньги на расчётный счёт.', action: null };
    case 'DISBURSED':
      return { now: 'Деньги зачислены. График платежей — ниже.', action: null };
    case 'REJECTED':
      return { now: 'Банк не может выдать кредит на этих условиях. Причины и что можно изменить — ниже.', action: null };
    case 'WITHDRAWN':
      return { now: 'Вы отозвали заявку. Можно подать новую.', action: null };
    case 'CLOSED':
      return { now: application.closeReason ? closeReasons[application.closeReason] : 'Заявка закрыта.', action: null };
  }
}

const eventTexts: Record<string, string> = {
  CREATED: 'Заявка создана',
  DOCUMENT_ADDED: 'Загружен документ',
  DOCUMENT_REMOVED: 'Удалён документ',
  SUBMIT: 'Заявка отправлена в банк',
  DATA_COMPLETE: 'Данные полные — заявка передана на проверку',
  DATA_INCOMPLETE: 'Банк запросил документы',
  INFO_PROVIDED: 'Вы дополнили заявку',
  INFO_EXPIRED: 'Срок ответа на запрос истёк',
  SOURCE_FAILED: 'Источник данных отвечает медленно — банк повторяет запрос',
  ENRICHMENT_DONE: 'Сведения о компании получены',
  SOURCE_TIMED_OUT: 'Источник данных не ответил — заявку рассмотрит специалист',
  AUTO_OFFER: 'Банк сделал предложение',
  TO_MANUAL_REVIEW: 'Заявку рассматривает специалист',
  AUTO_REJECT: 'Банк отказал',
  APPROVE: 'Специалист одобрил заявку',
  COUNTER_OFFER: 'Специалист предложил другие условия',
  REQUEST_INFO: 'Специалист запросил данные',
  REJECT: 'Специалист отказал',
  ACCEPT_OFFER: 'Вы приняли предложение',
  DECLINE_OFFER: 'Вы отказались от предложения',
  OFFER_EXPIRED: 'Срок предложения истёк',
  SIGN: 'Договор подписан',
  DISBURSEMENT_FAILED: 'Зачисление задерживается — банк повторяет операцию',
  DISBURSED: 'Деньги зачислены',
  WITHDRAW: 'Заявка отозвана'
};

export function eventText(entry: JournalEntry): string {
  const text = eventTexts[entry.event] ?? entry.event;
  const fileName = entry.payload.fileName;
  return typeof fileName === 'string' ? `${text}: ${fileName}` : text;
}

/** The history without noise: repeated retries of the same kind are shown once, with a count. */
export function historyForClient(entries: JournalEntry[]): Array<{ entry: JournalEntry; repeats: number }> {
  const result: Array<{ entry: JournalEntry; repeats: number }> = [];
  for (const entry of entries) {
    const last = result[result.length - 1];
    if (last && last.entry.event === entry.event && (entry.event === 'SOURCE_FAILED' || entry.event === 'DISBURSEMENT_FAILED')) {
      last.repeats += 1;
    } else {
      result.push({ entry, repeats: 1 });
    }
  }
  return result.reverse();
}
