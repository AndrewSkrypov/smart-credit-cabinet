import { render, screen, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { rub } from '../../../shared/format';
import { Application } from '../../../types';
import { OfferBlock } from './OfferBlock';

vi.mock('../../../api/client', () => ({ applicationsApi: {} }));

/** Testing Library compares with whitespace collapsed to plain spaces; Intl groups digits with U+202F. */
const plain = (text: string) => text.replace(/\s/g, ' ');

const application = {
  id: 'a1',
  status: 'OFFER_ISSUED',
  product: { code: 'WORKING_CAPITAL', name: 'Кредит на пополнение оборота', revolving: false },
  amount: 5_000_000,
  termMonths: 12,
  estimate: { rate: 20, monthlyPayment: 463_179.33 },
  process: {
    offer: {
      id: 'o1',
      kind: 'COUNTER',
      status: 'ISSUED',
      productCode: 'WORKING_CAPITAL',
      productName: 'Кредит на пополнение оборота',
      amount: 2_630_000,
      termMonths: 36,
      rate: 21.5,
      monthlyPayment: 99_752.13,
      totalPayment: 3_591_076.68,
      validUntil: '2026-10-21T12:00:00Z',
      changes: ['сумма уменьшена до 2 630 000 ₽', 'срок увеличен до 36 мес.']
    }
  }
} as unknown as Application;

describe('OfferBlock', () => {
  it('shows a counter offer next to the request, with what changed and the actions', () => {
    render(<OfferBlock application={application} onChanged={() => undefined} />);
    expect(screen.getByText('Банк предлагает другие условия')).toBeInTheDocument();
    expect(screen.getByText('Сумма уменьшена до 2 630 000 ₽')).toBeInTheDocument();
    const amountRow = screen.getByRole('rowheader', { name: 'Сумма' }).closest('tr')!;
    expect(within(amountRow).getByText(plain(rub(5_000_000)))).toBeInTheDocument();
    expect(within(amountRow).getByText(plain(rub(2_630_000)))).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Принять и перейти к подписанию' })).toBeEnabled();
  });
});
