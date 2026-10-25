import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { Application, AssessmentView } from '../../types';
import { RulesPage } from './RulesPage';
import { DecisionPanel } from './application/DecisionPanel';

const { current, products } = vi.hoisted(() => ({ current: vi.fn(), products: vi.fn() }));
vi.mock('../../api/client', () => ({ rulesApi: { current }, productsApi: { list: products } }));
vi.mock('../../auth/AuthProvider', () => ({ useAuth: () => ({ session: { user: { role: 'RISK_ADMIN' } } }) }));
vi.mock('../../hooks/useUnsavedChanges', () => ({ useUnsavedChanges: () => ({ dialog: null, request: (action: () => void) => action() }) }));

describe('native numeric fields', () => {
  it('sets numeric score and decimal percentage/margin modes on actual rule inputs', async () => {
    current.mockResolvedValue({ version: 1, versions: [], params: {
      maxLoad: 0.35, revenueMismatch: 0.3, weakAmountShare: 0.5, weakRevenueShare: 0.2,
      ratingCutoffs: { A: 85, B: 70, C: 55, D: 35 }, ratingMargins: { A: 0, B: 1, C: 3, D: 5 }, lowRiskOkved: [], highRiskOkved: []
    } });
    render(<MemoryRouter><RulesPage /></MemoryRouter>);
    for (const rating of ['A', 'B', 'C', 'D']) {
      expect(await screen.findByRole('textbox', { name: `${rating}: от баллов` })).toHaveAttribute('inputmode', 'numeric');
      expect(screen.getByRole('textbox', { name: `${rating}: надбавка, п.п.` })).toHaveAttribute('inputmode', 'decimal');
    }
    const percent = screen.getByRole('textbox', { name: 'Предельная долговая нагрузка' });
    expect(percent).toHaveAttribute('inputmode', 'decimal');
    fireEvent.change(percent, { target: { value: '35,5' } }); expect(percent).toHaveValue('35.5');
    expect(screen.getByRole('textbox', { name: 'Что и зачем меняется' })).not.toHaveAttribute('inputmode');
  });

  it('retains term digit entry clearing and money formatting in the actual counter-offer dialog', async () => {
    products.mockResolvedValue([]);
    const application = { id: 'a', product: { code: 'CREDIT' }, amount: 1_000_000, termMonths: 12 } as Application;
    const assessment = { automaticDecision: null, actions: ['COUNTER_OFFER'] } as AssessmentView;
    render(<DecisionPanel application={application} assessment={assessment} onDone={() => {}} />);
    fireEvent.click(screen.getByRole('button', { name: 'Другие условия' }));
    const term = await screen.findByRole('textbox', { name: 'Срок, месяцев' });
    expect(term).toHaveAttribute('inputmode', 'numeric');
    expect(screen.getByRole('textbox', { name: 'Сумма' })).toHaveAttribute('inputmode', 'numeric');
    fireEvent.change(term, { target: { value: '2a4' } }); expect(term).toHaveValue('24');
    fireEvent.change(term, { target: { value: '' } }); expect(term).toHaveValue('');
  });
});
