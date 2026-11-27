import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { agentApi } from '../../api/client';
import { extractInvoice, financingApi } from '../../api/financing';
import { writeSession } from '../../auth/session';
import { UserSession } from '../../types';
import { Application } from '../../types';
import { FinancingScenarioEditor } from './FinancingScenarioEditor';

vi.mock('../../api/client', () => ({ agentApi: { uploadDocument: vi.fn() } }));

vi.mock('../../api/financing', () => ({
  extractInvoice: vi.fn(),
  financingApi: { agent: vi.fn(), save: vi.fn() }
}));

const setActor = (token: string) => writeSession({ token, user: { id: token, role: 'AGENT' } } as UserSession);
afterEach(() => localStorage.clear());

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
  it.each(['before retry', 'during draft', 'during upload', 'during save'])('discards stale invoice evidence: %s', async (stage) => {
    vi.resetAllMocks();
    setActor('A');
    const application = { id: 'app-1', amount: 2_000_000, termMonths: 12 } as Application;
    const extraction = {
      mode: 'MANUAL' as const, amount: { value: 140_000, page: 1, quote: 'Сумма' },
      dates: [{ value: '2026-12-10', page: 1, quote: 'Дата' }], reason: null, needsConfirmation: true
    };
    vi.mocked(financingApi.agent).mockResolvedValue(null);
    vi.mocked(extractInvoice).mockResolvedValue(extraction);
    vi.mocked(agentApi.uploadDocument).mockResolvedValue({ id: 'document-B' } as Awaited<ReturnType<typeof agentApi.uploadDocument>>);
    if (stage === 'before retry') vi.mocked(agentApi.uploadDocument).mockRejectedValueOnce(new Error('Потерян ответ'));
    if (stage === 'during upload') vi.mocked(agentApi.uploadDocument).mockImplementationOnce(async () => {
      setActor('B');
      return { id: 'document-A' } as Awaited<ReturnType<typeof agentApi.uploadDocument>>;
    });
    vi.mocked(financingApi.save).mockImplementation(async (_, inputs) => ({
      revision: 1, status: 'DRAFT', inputs, loanAmount: 2_000_000, annualRatePercent: 20, options: []
    }));
    if (stage === 'during save') vi.mocked(financingApi.save).mockImplementationOnce(async (_, inputs) => {
      setActor('B');
      return { revision: 1, status: 'DRAFT', inputs, loanAmount: 2_000_000, annualRatePercent: 20, options: [] };
    });
    const saveDraft = vi.fn().mockResolvedValue(application);
    if (stage === 'during draft') saveDraft.mockImplementationOnce(async () => { setActor('B'); return application; });
    render(<FinancingScenarioEditor application={application} baseTerm={12} amount={2_000_000}
      savingDraft={false} saveDraft={saveDraft} onUncommittedChange={vi.fn()} />);
    await waitFor(() => expect(screen.getByRole('button', { name: 'Прочитать счёт PDF' })).toBeEnabled());
    async function selectInvoice() {
      fireEvent.change(screen.getByLabelText('Счёт PDF для примерки'), {
        target: { files: [new File(['synthetic'], 'invoice.pdf', { type: 'application/pdf' })] }
      });
      const date = await screen.findByRole('radio', { name: /Дата/ });
      fireEvent.click(screen.getByRole('button', { name: 'Использовать сумму из счёта' }));
      fireEvent.click(date);
    }
    await selectInvoice();
    fireEvent.change(screen.getByLabelText('Остаток до поступления'), { target: { value: '150000' } });
    fireEvent.change(screen.getByLabelText('Дата платежа'), { target: { value: '2026-12-15' } });
    fireEvent.click(screen.getByRole('button', { name: /Рассчитать и сохранить/ }));
    if (stage === 'before retry') {
      await screen.findByText('Потерян ответ');
      setActor('B');
      fireEvent.click(screen.getByRole('button', { name: /Рассчитать и сохранить/ }));
    }
    await screen.findByText('Выберите файл заново, чтобы продолжить.');
    expect(agentApi.uploadDocument).toHaveBeenCalledTimes(stage === 'during draft' ? 0 : 1);
    expect(financingApi.save).toHaveBeenCalledTimes(stage === 'during save' ? 1 : 0);
    expect(screen.queryByRole('table', { name: 'Сравнение сценариев финансирования' })).toBeNull();
    const oldKey = vi.mocked(agentApi.uploadDocument).mock.calls[0]?.[3];
    await selectInvoice();
    fireEvent.click(screen.getByRole('button', { name: /Рассчитать и сохранить/ }));
    await waitFor(() => expect(financingApi.save).toHaveBeenCalledTimes(stage === 'during save' ? 2 : 1));
    expect(vi.mocked(agentApi.uploadDocument).mock.lastCall?.[3]).not.toBe(oldKey);
    expect(vi.mocked(financingApi.save).mock.lastCall?.[1].source?.documentId).toBe('document-B');
  });

  it('discards extraction completed under a changed actor and accepts a new file', async () => {
    vi.resetAllMocks();
    setActor('A');
    vi.mocked(financingApi.agent).mockResolvedValue(null);
    const extraction = {
      mode: 'MANUAL' as const, amount: { value: 140_000, page: 1, quote: 'Сумма' },
      dates: [{ value: '2026-12-10', page: 1, quote: 'Дата' }], reason: null, needsConfirmation: true
    };
    vi.mocked(extractInvoice).mockResolvedValue(extraction).mockImplementationOnce(async () => {
      setActor('B');
      return extraction;
    });
    const application = { id: 'app-1', amount: 2_000_000, termMonths: 12 } as Application;
    render(<FinancingScenarioEditor application={application} baseTerm={12} amount={2_000_000}
      savingDraft={false} saveDraft={vi.fn()} onUncommittedChange={vi.fn()} />);
    await waitFor(() => expect(screen.getByRole('button', { name: 'Прочитать счёт PDF' })).toBeEnabled());
    const select = () => fireEvent.change(screen.getByLabelText('Счёт PDF для примерки'), {
      target: { files: [new File(['synthetic'], 'invoice.pdf', { type: 'application/pdf' })] }
    });
    select();
    await screen.findByText('Выберите файл заново, чтобы продолжить.');
    expect(screen.queryByRole('radio', { name: /Дата/ })).toBeNull();
    expect(agentApi.uploadDocument).not.toHaveBeenCalled();
    select();
    expect(await screen.findByRole('radio', { name: /Дата/ })).toBeInTheDocument();
  });

});
