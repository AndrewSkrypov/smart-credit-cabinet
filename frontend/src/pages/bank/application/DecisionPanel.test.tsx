import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { Application, AssessmentView } from '../../../types';
import { DecisionPanel } from './DecisionPanel';

const approve = vi.fn().mockResolvedValue({ status: 'OFFER_ISSUED' });

vi.mock('../../../api/client', () => ({
  productsApi: { list: vi.fn().mockResolvedValue([]) },
  underwritingApi: { approve: (...args: unknown[]) => approve(...args) }
}));

const application = { id: 'a1', amount: 3_000_000, termMonths: 12, product: { code: 'WORKING_CAPITAL', name: 'Кредит', revolving: false } } as unknown as Application;

function assessment(recommendation: 'APPROVE' | 'REJECT'): AssessmentView {
  return {
    applicationId: 'a1',
    status: 'MANUAL_REVIEW',
    registry: null,
    tax: null,
    bureau: null,
    bank: null,
    turnoverVariation: null,
    discrepancies: [],
    latestDecision: null,
    actions: ['APPROVE', 'COUNTER_OFFER', 'REQUEST_INFO', 'REJECT'],
    automaticDecision: {
      id: 'd1',
      decidedByType: 'SYSTEM',
      decidedBy: null,
      ruleSetVersion: 1,
      score: 58,
      rating: 'C',
      stopFactors: [],
      factors: [],
      outcome: 'MANUAL_REVIEW',
      reasonCode: null,
      manualReasons: [{ code: 'RATING_C', title: 'Рейтинг C — серая зона' }],
      recommendation: { outcome: recommendation, terms: null, rejectReason: null, notes: [] },
      comment: null,
      createdAt: '2026-10-21T10:00:00Z'
    }
  };
}

describe('DecisionPanel', () => {
  it('approves along the recommendation without a comment', async () => {
    const done = vi.fn();
    render(<DecisionPanel application={application} assessment={assessment('APPROVE')} onDone={done} />);
    expect(screen.getByText('Одобрить на запрошенных условиях')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Одобрить' }));
    await userEvent.click(screen.getByRole('button', { name: 'Сохранить решение' }));
    expect(approve).toHaveBeenCalledWith('a1', undefined);
    expect(done).toHaveBeenCalled();
  });

  it('asks for a reason before approving against the recommendation', async () => {
    render(<DecisionPanel application={application} assessment={assessment('REJECT')} onDone={() => undefined} />);
    await userEvent.click(screen.getByRole('button', { name: 'Одобрить' }));
    const save = screen.getByRole('button', { name: 'Сохранить решение' });
    expect(save).toBeDisabled();
    await userEvent.type(screen.getByLabelText(/Обоснование/), 'Залог покрывает риск');
    expect(save).toBeEnabled();
  });
});
