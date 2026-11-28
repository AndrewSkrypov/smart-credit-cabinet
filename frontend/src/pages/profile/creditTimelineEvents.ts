import { statusTitle } from '../../shared/applicationText';
import { Application, ApplicationSummary, JournalEntry, LoanAccount } from '../../types';

export type CreditEventKind = 'status' | 'deadline' | 'income' | 'paid' | 'scheduled';

export interface CreditEvent {
  id: string;
  date: string;
  time: number;
  title: string;
  kind: CreditEventKind;
  amount?: number;
}

export function creditTimeline(
  summary: ApplicationSummary,
  history: JournalEntry[],
  application: Application | null,
  account: LoanAccount | null
): CreditEvent[] {
  const events: CreditEvent[] = [];
  const add = (id: string, date: string, title: string, kind: CreditEventKind, amount?: number) => {
    const time = Date.parse(date);
    if (Number.isFinite(time)) events.push({ id, date, time, title, kind, amount });
  };

  const seenStatuses = new Set<string>();
  const addStatus = (id: string, date: string, status: ApplicationSummary['status']) => {
    const identity = `${Date.parse(date)}:${status}`;
    if (seenStatuses.has(identity)) return;
    seenStatuses.add(identity);
    add(id, date, statusTitle(status), 'status');
  };
  history.filter((entry) => entry.fromStatus !== entry.toStatus)
    .forEach((entry) => addStatus(entry.id, entry.occurredAt, entry.toStatus));
  const current = application ?? summary;
  addStatus('current', current.statusChangedAt, current.status);
  const process = application?.process;

  if (current.status === 'OFFER_ISSUED' && process?.offer?.status === 'ISSUED') {
    add('offer-deadline', process.offer.validUntil, 'Принять предложение до', 'deadline', process.offer.amount);
  } else if (current.status === 'INFO_REQUESTED' && process?.infoRequest) {
    add('info-deadline', process.infoRequest.deadline, 'Предоставить данные до', 'deadline');
  } else if (current.status === 'SIGNING' && process?.signing?.expiresAt) {
    add('signing-deadline', process.signing.expiresAt, 'Код подписания действует до', 'deadline');
  } else if (current.actionDeadline && !['DISBURSED', 'REJECTED', 'WITHDRAWN', 'CLOSED'].includes(current.status)) {
    add('action-deadline', current.actionDeadline, current.actionLabel ?? 'Срок следующего действия', 'deadline');
  }

  if (account?.tranches.length) {
    account.tranches.forEach((tranche) => add(tranche.id, tranche.disbursedAt, `Выдан транш № ${tranche.trancheNumber}`, 'income', tranche.amount));
  } else if (account || process?.loan) {
    const date = account?.disbursedAt ?? process?.loan?.disbursedAt;
    if (date) add('disbursed', date, 'Кредит выдан', 'income', account?.disbursedAmount ?? process?.loan?.amount);
  }

  account?.payments.forEach((payment) => {
    if (payment.status === 'PAID' && payment.paidAt) {
      add(payment.id, payment.paidAt, `Платёж № ${payment.paymentNumber} внесён`, 'paid', payment.paymentAmount);
    } else if (payment.status === 'SCHEDULED' && account.status === 'ACTIVE') {
      add(payment.id, payment.dueDate, `Платёж № ${payment.paymentNumber} по графику`, 'scheduled', payment.paymentAmount);
    }
  });
  if (account?.repaidAt) add('repaid', account.repaidAt, 'Кредит погашен', 'status');
  const maturityDate = account?.maturityDate ?? process?.loan?.maturityDate;
  if (maturityDate && account?.status !== 'REPAID') {
    add('maturity', maturityDate, 'Окончание срока по договору', 'deadline');
  }

  return events.sort((left, right) => left.time - right.time);
}
