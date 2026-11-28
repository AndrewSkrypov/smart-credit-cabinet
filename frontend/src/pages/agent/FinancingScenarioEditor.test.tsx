import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { agentApi } from '../../api/client';
import { extractInvoice, financingApi, FinancingPreview } from '../../api/financing';
import { writeSession } from '../../auth/session';
import { UserSession } from '../../types';
import { Application } from '../../types';
import { FinancingScenarioEditor } from './FinancingScenarioEditor';

vi.mock('../../api/client', () => ({ agentApi: { uploadDocument: vi.fn() } }));

vi.mock('../../api/financing', () => ({
  extractInvoice: vi.fn(),
  financingApi: { agent: vi.fn(), preview: vi.fn(), save: vi.fn() }
}));

const setActor = (token: string) => writeSession({ token, user: { id: token, role: 'AGENT' } } as UserSession);
afterEach(() => localStorage.clear());

function previewAt(delayDays: 5 | 6): FinancingPreview {
  const included = delayDays === 5;
  return {
    source: 'credit',
    asOf: '2026-11-28T12:00:00Z',
    preview: true,
    basis: { applicationVersion: '3', productCode: 'WORKING_CAPITAL', productVersion: '2', savedScenarioRevision: null },
    inputsUsed: {
      currency: 'RUB', paymentModel: 'ANNUITY', loanAmountRub: '2000000', annualRatePercent: '20',
      baseTermMonths: 12, alternativeTermMonths: 24, openingBalanceRub: '150000', expectedReceiptAmountRub: '140000',
      expectedReceiptDate: '2026-12-10', paymentDate: '2026-12-15', delayDays,
      receiptIncludedByPayment: included, lastIncludedDelayDays: 5
    },
    options: [
      { termMonths: 12, monthlyPaymentRub: '185269.00', totalInterestRub: '223228.00',
        balanceOnPaymentDateRub: included ? '104731.00' : '-35269.00' },
      { termMonths: 24, monthlyPaymentRub: '101792.00', totalInterestRub: '442999.00',
        balanceOnPaymentDateRub: included ? '188208.00' : '48208.00' }
    ],
    cabinetPath: '/agent/applications/app-1'
  };
}

function fillPreviewForm() {
  fireEvent.change(screen.getByLabelText('Остаток до поступления'), { target: { value: '150000' } });
  fireEvent.change(screen.getByLabelText('Сумма ожидаемого поступления'), { target: { value: '140000' } });
  fireEvent.change(screen.getByLabelText('Дата ожидаемого поступления'), { target: { value: '2026-12-10' } });
  fireEvent.change(screen.getByLabelText('Дата платежа'), { target: { value: '2026-12-15' } });
  fireEvent.change(screen.getByLabelText('Задержка поступления, дней'), { target: { value: '5' } });
}

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
    expect(screen.getByRole('button', { name: 'Сохранить выбранный вариант' })).toBeDisabled();

    fireEvent.click(screen.getByRole('button', { name: 'Использовать сумму из счёта' }));
    fireEvent.click(firstDate);
    fireEvent.change(screen.getByLabelText('Остаток до поступления'), { target: { value: '150000' } });
    fireEvent.change(screen.getByLabelText('Дата платежа'), { target: { value: '2026-12-15' } });
    fireEvent.change(screen.getByLabelText('Задержка поступления, дней'), { target: { value: '14' } });
    fireEvent.click(screen.getByRole('radio', { name: '24 месяца' }));
    fireEvent.click(screen.getByRole('button', { name: 'Сохранить выбранный вариант' }));

    if (lostReply || lostSave) {
      await screen.findByText('Нет соединения с сервером');
      expect(financingApi.save).toHaveBeenCalledTimes(lostSave ? 1 : 0);
      fireEvent.click(screen.getByRole('button', { name: 'Сохранить выбранный вариант' }));
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
    fireEvent.click(screen.getByRole('button', { name: 'Сохранить выбранный вариант' }));
    if (stage === 'before retry') {
      await screen.findByText('Потерян ответ');
      setActor('B');
      fireEvent.click(screen.getByRole('button', { name: 'Сохранить выбранный вариант' }));
    }
    await screen.findByText('Выберите файл заново, чтобы продолжить.');
    expect(agentApi.uploadDocument).toHaveBeenCalledTimes(stage === 'during draft' ? 0 : 1);
    expect(financingApi.save).toHaveBeenCalledTimes(stage === 'during save' ? 1 : 0);
    expect(screen.queryByRole('table', { name: 'Сравнение сценариев финансирования' })).toBeNull();
    const oldKey = vi.mocked(agentApi.uploadDocument).mock.calls[0]?.[3];
    await selectInvoice();
    fireEvent.click(screen.getByRole('button', { name: 'Сохранить выбранный вариант' }));
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

  it('explains the five-day boundary and recalculates at six days without saving', async () => {
    vi.resetAllMocks();
    setActor('A');
    vi.mocked(financingApi.agent).mockResolvedValue(null);
    vi.mocked(financingApi.preview).mockResolvedValueOnce(previewAt(5)).mockResolvedValueOnce(previewAt(6));
    vi.mocked(extractInvoice).mockResolvedValue({
      mode: 'MANUAL', amount: { value: 140_000, page: 1, quote: 'Сумма' },
      dates: [{ value: '2026-12-10', page: 1, quote: 'Дата' }], reason: null
    });
    const application = { id: 'app-1', amount: 1_000_000, termMonths: 18 } as Application;
    const saveDraft = vi.fn();
    render(<FinancingScenarioEditor application={application} baseTerm={12} amount={2_000_000}
      savingDraft={false} saveDraft={saveDraft} onUncommittedChange={vi.fn()} />);
    await waitFor(() => expect(screen.getByRole('button', { name: 'Прочитать счёт PDF' })).toBeEnabled());
    fillPreviewForm();
    fireEvent.change(screen.getByLabelText('Счёт PDF для примерки'), {
      target: { files: [new File(['synthetic'], 'invoice.pdf', { type: 'application/pdf' })] }
    });
    fireEvent.click(await screen.findByRole('button', { name: 'Использовать сумму из счёта' }));
    fireEvent.click(screen.getByRole('radio', { name: /Дата/ }));

    fireEvent.click(screen.getByRole('button', { name: 'Рассчитать примерку' }));
    expect(await screen.findByText(/Поступление учтено в остатке/)).toHaveTextContent('до 5 дн. включительно');
    expect(financingApi.preview).toHaveBeenNthCalledWith(1, 'app-1', {
      mode: 'WEB_FORM', openingBalance: 150_000, expectedReceiptAmount: 140_000,
      expectedReceiptDate: '2026-12-10', paymentDate: '2026-12-15', delayDays: 5, alternativeTermMonths: 24,
      draftTermsOverride: { amount: 2_000_000, baseTermMonths: 12 }
    });

    fireEvent.change(screen.getByLabelText('Задержка поступления, дней'), { target: { value: '6' } });
    expect(screen.queryByRole('region', { name: 'Предварительный расчёт' })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Рассчитать примерку' }));
    expect(await screen.findByText(/Поступление ожидается после даты платежа/)).toBeInTheDocument();
    expect(screen.getByText(/При сроке 12 месяцев на дату платежа не хватает/)).toHaveTextContent('35 269,00 ₽');
    expect(financingApi.preview).toHaveBeenNthCalledWith(2, 'app-1', expect.objectContaining({ delayDays: 6 }));
    expect(screen.getByRole('button', { name: 'Сохранить выбранный вариант' })).toBeEnabled();
    expect(extractInvoice).toHaveBeenCalledTimes(1);
    expect(saveDraft).not.toHaveBeenCalled();
    expect(financingApi.save).not.toHaveBeenCalled();
    expect(agentApi.uploadDocument).not.toHaveBeenCalled();
  });

  it.each(['draft amount', 'actor'])('ignores a preview completed after a change to its %s', async (changed) => {
    vi.resetAllMocks();
    setActor('A');
    vi.mocked(financingApi.agent).mockResolvedValue(null);
    let finishPreview: (preview: FinancingPreview) => void = () => undefined;
    vi.mocked(financingApi.preview).mockReturnValueOnce(new Promise((resolve) => { finishPreview = resolve; }));
    const application = { id: 'app-1', amount: 2_000_000, termMonths: 12 } as Application;
    const saveDraft = vi.fn();
    const onUncommittedChange = vi.fn();
    const editor = (amount: number) => <FinancingScenarioEditor application={application} baseTerm={12} amount={amount}
      savingDraft={false} saveDraft={saveDraft} onUncommittedChange={onUncommittedChange} />;
    const { rerender } = render(editor(2_000_000));
    await waitFor(() => expect(screen.getByRole('button', { name: 'Прочитать счёт PDF' })).toBeEnabled());
    fillPreviewForm();
    fireEvent.click(screen.getByRole('button', { name: 'Рассчитать примерку' }));

    if (changed === 'actor') setActor('B');
    else rerender(editor(3_000_000));
    finishPreview(previewAt(5));
    await waitFor(() => expect(screen.getByRole('button', { name: 'Рассчитать примерку' })).toBeEnabled());
    expect(screen.queryByRole('region', { name: 'Предварительный расчёт' })).not.toBeInTheDocument();
    expect(financingApi.save).not.toHaveBeenCalled();
  });

  it.each(['application version', 'catalog version', 'catalog availability'])('hides a displayed preview after a change to %s', async (changed) => {
    vi.resetAllMocks();
    setActor('A');
    vi.mocked(financingApi.agent).mockResolvedValue(null);
    vi.mocked(financingApi.preview).mockResolvedValue(previewAt(5));
    const application = {
      id: 'app-1', amount: 2_000_000, termMonths: 12, version: 3, product: { version: 2 }, currentProduct: { version: 2 }
    } as Application;
    const saveDraft = vi.fn();
    const onUncommittedChange = vi.fn();
    const editor = (current: Application) => <FinancingScenarioEditor application={current} baseTerm={12} amount={current.amount}
      savingDraft={false} saveDraft={saveDraft} onUncommittedChange={onUncommittedChange} />;
    const { rerender } = render(editor(application));
    await waitFor(() => expect(screen.getByRole('button', { name: 'Прочитать счёт PDF' })).toBeEnabled());
    fillPreviewForm();
    fireEvent.click(screen.getByRole('button', { name: 'Рассчитать примерку' }));
    expect(await screen.findByRole('region', { name: 'Предварительный расчёт' })).toBeInTheDocument();

    const updated: Application = { ...application };
    if (changed === 'application version') updated.version = 4;
    else if (changed === 'catalog version') updated.currentProduct = { ...application.currentProduct!, version: 3 };
    else updated.currentProduct = null;
    rerender(editor(updated));
    expect(screen.queryByRole('region', { name: 'Предварительный расчёт' })).not.toBeInTheDocument();
    expect(financingApi.save).not.toHaveBeenCalled();
  });
});
