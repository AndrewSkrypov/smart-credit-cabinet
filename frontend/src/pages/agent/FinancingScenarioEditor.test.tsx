import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { agentApi } from '../../api/client';
import { extractInvoice, financingApi } from '../../api/financing';
import { Application } from '../../types';
import { FinancingScenarioEditor } from './FinancingScenarioEditor';

vi.mock('../../api/client', () => ({ agentApi: { uploadDocument: vi.fn() } }));

vi.mock('../../api/financing', () => ({
  extractInvoice: vi.fn(),
  financingApi: { agent: vi.fn(), save: vi.fn() }
}));

describe('FinancingScenarioEditor', () => {
  it.each(['success', 'upload reply lost', 'save reply lost'])('requires explicit evidence and preserves the upload: %s', async (mode) => {
    const lostReply = mode === 'upload reply lost';
    const lostSave = mode === 'save reply lost';
    vi.clearAllMocks();
    vi.mocked(financingApi.agent).mockResolvedValue(null);
    vi.mocked(agentApi.uploadDocument).mockResolvedValue({ id: 'invoice-document' } as Awaited<ReturnType<typeof agentApi.uploadDocument>>);
    if (lostReply) vi.mocked(agentApi.uploadDocument).mockRejectedValueOnce(new Error('Нет соединения с сервером'));
    vi.mocked(extractInvoice).mockResolvedValue({
      mode: 'MANUAL',
      amount: { value: 140_000, page: 1, quote: 'К оплате 140 000 руб.' },
      dates: [
        { value: '2026-12-10', page: 1, quote: 'Срок оплаты 10.12.2026' },
        { value: '2026-12-24', page: 1, quote: 'Действителен до 24.12.2026' }
      ],
      reason: null,
      needsConfirmation: true
    });
    vi.mocked(financingApi.save).mockImplementation(async (_, inputs) => ({
      revision: 1,
      status: 'DRAFT',
      inputs,
      loanAmount: 2_000_000,
      annualRatePercent: 20,
      options: [
        { termMonths: 12, monthlyPayment: 185_269, totalInterest: 223_228, balanceOnPaymentDate: -35_269 },
        { termMonths: 24, monthlyPayment: 101_792, totalInterest: 442_999, balanceOnPaymentDate: 48_208 }
      ]
    }));
    if (lostSave) vi.mocked(financingApi.save).mockRejectedValueOnce(new Error('Нет соединения с сервером'));
    const application = { id: 'app-1', amount: 2_000_000, termMonths: 12 } as Application;
    const saveDraft = vi.fn().mockResolvedValue(application);
    render(<FinancingScenarioEditor application={application} baseTerm={12} amount={2_000_000}
      savingDraft={false} saveDraft={saveDraft} onUncommittedChange={vi.fn()} />);

    await waitFor(() => expect(screen.getByRole('button', { name: 'Прочитать счёт PDF' })).toBeEnabled());
    fireEvent.change(screen.getByLabelText('Счёт PDF для примерки'), {
      target: { files: [new File(['synthetic'], 'invoice.pdf', { type: 'application/pdf' })] }
    });

    const amount = screen.getByLabelText('Сумма ожидаемого поступления');
    const firstDate = await screen.findByRole('radio', { name: /Срок оплаты/ });
    expect(amount).toHaveValue('');
    expect(firstDate).not.toBeChecked();
    expect(screen.getByRole('button', { name: /Рассчитать и сохранить/ })).toBeDisabled();

    fireEvent.click(screen.getByRole('button', { name: 'Использовать сумму из счёта' }));
    fireEvent.click(firstDate);
    fireEvent.change(screen.getByLabelText('Остаток до поступления'), { target: { value: '150000' } });
    fireEvent.change(screen.getByLabelText('Дата платежа'), { target: { value: '2026-12-15' } });
    fireEvent.change(screen.getByLabelText('Задержка поступления, дней'), { target: { value: '14' } });
    fireEvent.click(screen.getByRole('radio', { name: '24 месяца' }));
    fireEvent.click(screen.getByRole('button', { name: /Рассчитать и сохранить/ }));

    if (lostReply || lostSave) {
      await screen.findByText('Нет соединения с сервером');
      expect(financingApi.save).toHaveBeenCalledTimes(lostSave ? 1 : 0);
      fireEvent.click(screen.getByRole('button', { name: /Рассчитать и сохранить/ }));
    }

    await waitFor(() => expect(financingApi.save).toHaveBeenCalledWith('app-1', expect.objectContaining({
      expectedReceiptAmount: 140_000,
      expectedReceiptDate: '2026-12-10',
      selectedTermMonths: 24,
      delayDays: 14,
      source: { page: 1, amountQuote: 'К оплате 140 000 руб.', dateQuote: 'Срок оплаты 10.12.2026', documentId: 'invoice-document' }
    })));
    const uploads = vi.mocked(agentApi.uploadDocument).mock.calls;
    expect(uploads).toHaveLength(lostReply ? 2 : 1);
    expect(uploads[0][3]).toEqual(expect.any(String));
    if (lostReply) expect(uploads[1][3]).toBe(uploads[0][3]);
    expect(await screen.findByRole('table', { name: 'Сравнение сценариев финансирования' })).toBeInTheDocument();
  });
});
