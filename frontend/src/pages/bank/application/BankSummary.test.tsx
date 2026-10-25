import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { Application, AssessmentView, DecisionView } from '../../../types';
import { BankSummary } from './BankSummary';
import { SourcesBlock } from './AssessmentBlocks';

const application = { product: { name: 'Оборотный кредит' }, amount: 100000, termMonths: 12,
  purposeTitle: 'Закупка', status: 'MANUAL_REVIEW', process: { outcome: null } } as unknown as Application;
const assessment = { status: 'MANUAL_REVIEW', registry: null, tax: null, bureau: null, bank: null,
  latestDecision: null, automaticDecision: null, actions: ['REQUEST_INFO'] } as unknown as AssessmentView;

describe('bank summary and source receipt dates', () => {
  it('shows only permitted actions and unknown sources, without inventing a decision', () => {
    render(<BankSummary application={application} assessment={assessment} stale={false} />);
    expect(screen.getByText(/Последнее решение:/).parentElement).toHaveTextContent('ещё нет');
    expect(screen.getByText(/Не получены ответы:/)).toHaveTextContent('БКИ');
    expect(screen.getByText(/Доступно вам/).parentElement).toHaveTextContent('Запросить данные');
    expect(screen.queryByText(/Одобрить/)).not.toBeInTheDocument();
    expect(screen.getByText(/Кредитное решение принимает андеррайтер/)).toBeInTheDocument();
  });

  it.each([['DISBURSED', 'Кредит выдан.'], ['WITHDRAWN', 'Заявка отозвана.'], ['CLOSED', 'Заявка закрыта.']])('explains the terminal result %s without inventing a role action', (status, expected) => {
    render(<BankSummary application={{ ...application, status } as Application} assessment={{ ...assessment, actions: [] }} stale={false} />);
    expect(screen.getByText(expected, { exact: false })).toBeInTheDocument();
    expect(screen.queryByText(/Для вашей роли действий/)).not.toBeInTheDocument();
  });

  it('uses the latest manual decision rather than the previous automatic recommendation', () => {
    render(<BankSummary application={{ ...application, status: 'REJECTED' }} assessment={{ ...assessment, actions: [],
      automaticDecision: { outcome: 'APPROVE' } as DecisionView,
      latestDecision: { outcome: 'REJECT', decidedByType: 'UNDERWRITER', createdAt: '2026-10-24T10:00:00Z',
        stopFactors: [], manualReasons: [], comment: 'Подтверждён риск' } as unknown as DecisionView }} stale={false} />);
    expect(screen.getByText(/Последнее решение:/).parentElement).toHaveTextContent('Отказ · специалистом');
    expect(screen.getByText(/Подтверждён риск/)).toBeInTheDocument();
    expect(screen.queryByText(/Одобрено/)).not.toBeInTheDocument();
  });

  it('does not offer stale actions after a refresh failure', () => {
    render(<BankSummary application={application} assessment={assessment} stale />);
    expect(screen.getByText(/Дождитесь обновления/)).toBeInTheDocument();
    expect(screen.queryByText('Запросить данные')).not.toBeInTheDocument();
  });

  it('does not treat a received no-account response as known account turnover', () => {
    render(<BankSummary application={application} assessment={{ ...assessment,
      bank: { client: false, settlementAccount: null, monthlyInflows: [] } }} stale={false} />);
    expect(screen.getByText(/Обороты по счёту в банке неизвестны/)).toBeInTheDocument();
  });

  it('keeps the reporting year distinct from the time the response reached the cabinet', () => {
    render(<SourcesBlock assessment={{ ...assessment, sourceFetchedAt: { TAX: '2026-10-24T10:00:00Z' },
      tax: { hasReports: true, reportingYear: 2024, revenue: null, expenses: null, taxDebt: 0, averageHeadcount: null, taxRegime: null, massAddress: false } }} />);
    expect(screen.getByText('2024')).toBeInTheDocument();
    expect(screen.getByText(/Получено:/)).toHaveTextContent('24 октября');
    expect(screen.getByText(/не дата актуальности/)).toBeInTheDocument();
  });
});
