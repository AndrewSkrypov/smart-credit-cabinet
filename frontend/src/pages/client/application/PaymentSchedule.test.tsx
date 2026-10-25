import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, it, vi } from 'vitest';
import { applicationsApi } from '../../../api/client';
import { rubExact } from '../../../shared/format';
import { PaymentSchedule } from './PaymentSchedule';

vi.mock('../../../api/client', () => ({ applicationsApi: { schedule: vi.fn() } }));

it('shows the actual final and maximum payment before accepting, and the server rows on demand', async () => {
  vi.mocked(applicationsApi.schedule).mockResolvedValue({
    rate: 20, monthlyPayment: 100, totalPayment: 700, overpayment: 100, revolving: true,
    schedule: [
      { month: 1, payment: 100, principal: 80, interest: 20, balance: 520 },
      { month: 2, payment: 100, principal: 90, interest: 10, balance: 430 },
      { month: 3, payment: 500, principal: 430, interest: 70, balance: 0 }
    ]
  });
  render(<PaymentSchedule applicationId="synthetic" revision="offer-1" preliminary />);
  expect(await screen.findByText('Последний платёж')).toBeInTheDocument();
  expect(screen.getByText('Последний платёж').parentElement).toHaveTextContent(rubExact(500));
  expect(screen.getByText('Максимальный платёж').parentElement).toHaveTextContent(rubExact(500));
  expect(screen.getByText(/Для возобновляемого лимита/)).toBeInTheDocument();
  expect(screen.getByRole('table')).not.toBeVisible();
  await userEvent.click(screen.getByText('Показать все платежи по месяцам'));
  const table = screen.getByRole('table', { name: 'График платежей' });
  expect(within(table).getAllByRole('row')).toHaveLength(4);
});
