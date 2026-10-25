import { act, fireEvent, render, screen } from '@testing-library/react';
import { useState } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { Application, ApplicationInput } from '../../../types';
import { DraftForm } from './DraftForm';

const { update, submit, products } = vi.hoisted(() => ({ update: vi.fn(), submit: vi.fn(), products: vi.fn() }));
vi.mock('../../../api/client', () => ({ applicationsApi: { update, submit }, productsApi: { list: products } }));

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: Error) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}
const initial = {
  id: 'draft', product: { code: 'working', name: 'Кредит', version: 1 }, purpose: 'WORKING_CAPITAL', purposeTitle: 'Оборот',
  amount: 1_000_000, termMonths: 12, declaredRevenue12m: 12_000_000, declaredMonthlyDebtPayments: 0,
  bureauConsent: false, submissionProblems: [], estimate: null
} as unknown as Application;

function mount() {
  const saved = vi.fn(); const submitted = vi.fn();
  function Harness() {
    const [application, setApplication] = useState(initial);
    return <DraftForm application={application} onSaved={next => { saved(next); setApplication(next); }} onSubmitted={submitted} />;
  }
  return { ...render(<Harness />), saved, submitted };
}
const amount = () => screen.getByRole('textbox', { name: 'Сумма' });
const send = () => screen.getByRole('button', { name: /Отправить в банк|Отправляем/ });
const changeAmount = (value: string) => fireEvent.change(amount(), { target: { value } });
const tick = () => act(async () => { vi.advanceTimersByTime(700); });

describe('draft revision persistence', () => {
  beforeEach(() => { vi.useFakeTimers(); update.mockReset(); submit.mockReset(); products.mockResolvedValue([]); });
  afterEach(() => { vi.useRealTimers(); });

  it('serializes writes, coalesces edits and submits exactly the visible complete revision', async () => {
    const first = deferred<Application>(); const last = deferred<Application>();
    let server = initial;
    update.mockImplementationOnce(async (_id, input: ApplicationInput) => { await first.promise; return server = { ...initial, ...input }; })
      .mockImplementationOnce(async (_id, input: ApplicationInput) => { await last.promise; return server = { ...initial, ...input }; });
    submit.mockImplementation(async () => server);
    const ui = mount();
    expect(screen.getByRole('textbox', { name: 'Срок, месяцев' })).toHaveAttribute('inputmode', 'numeric');
    changeAmount('2000000'); await tick();
    changeAmount('2500000');
    changeAmount('3000000');
    fireEvent.change(screen.getByRole('textbox', { name: 'Срок, месяцев' }), { target: { value: '24' } });
    fireEvent.change(screen.getByRole('textbox', { name: /Выручка за/ }), { target: { value: '36000000' } });
    fireEvent.change(screen.getByRole('textbox', { name: /Платежи по кредитам/ }), { target: { value: '25000' } });
    fireEvent.click(screen.getByRole('checkbox', { name: /Согласен на запрос/ }));
    await tick();
    // The second request cannot finish first: it must not exist until the first settles.
    expect(update).toHaveBeenCalledTimes(1);
    expect(screen.queryByText('Сохранено')).not.toBeInTheDocument();
    fireEvent.click(send()); fireEvent.click(send());
    expect(amount()).toBeDisabled();
    fireEvent.change(amount(), { target: { value: '9000000' } });
    await act(async () => first.resolve(initial));
    expect(ui.saved).not.toHaveBeenCalled();
    expect(submit).not.toHaveBeenCalled();
    expect(update).toHaveBeenCalledTimes(2);
    const expected = { amount: 3_000_000, termMonths: 24, declaredRevenue12m: 36_000_000, declaredMonthlyDebtPayments: 25_000, bureauConsent: true };
    expect(update.mock.calls[1][1]).toMatchObject(expected);
    await act(async () => last.resolve(initial));
    expect(ui.saved).toHaveBeenCalledTimes(1);
    expect(ui.saved.mock.calls[0][0]).toMatchObject(expected);
    expect(submit).toHaveBeenCalledTimes(1);
    expect(ui.submitted.mock.calls[0][0]).toMatchObject(expected);
    expect(amount()).toHaveValue('3\u00a0000\u00a0000');
  });

  it('saves immediately on submit without waiting for debounce', async () => {
    const pending = deferred<Application>();
    update.mockReturnValue(pending.promise); submit.mockResolvedValue(initial);
    mount(); changeAmount('4000000'); fireEvent.click(send());
    expect(update).toHaveBeenCalledTimes(1);
    expect(update.mock.calls[0][1].amount).toBe(4_000_000);
    expect(submit).not.toHaveBeenCalled();
    await act(async () => pending.resolve({ ...initial, amount: 4_000_000 }));
    expect(submit).toHaveBeenCalledTimes(1);
    await tick(); expect(update).toHaveBeenCalledTimes(1);
  });

  it('continues with newer edits after the older write fails but never submits a failed latest revision', async () => {
    const first = deferred<Application>(); const last = deferred<Application>();
    update.mockReturnValueOnce(first.promise).mockReturnValueOnce(last.promise);
    const ui = mount(); changeAmount('2000000'); await tick(); changeAmount('3000000');
    fireEvent.click(send());
    await act(async () => first.reject(new Error('Сбой первого сохранения')));
    expect(update).toHaveBeenCalledTimes(2);
    expect(submit).not.toHaveBeenCalled();
    await act(async () => last.reject(new Error('Сбой актуального сохранения')));
    expect(screen.getByText('Не сохранено')).toBeInTheDocument();
    expect(screen.getByText('Сбой актуального сохранения')).toBeInTheDocument();
    expect(ui.saved).not.toHaveBeenCalled(); expect(submit).not.toHaveBeenCalled();
    expect(amount()).not.toBeDisabled();
    update.mockResolvedValueOnce({ ...initial, amount: 3_000_000 }); submit.mockResolvedValue(initial);
    await act(async () => fireEvent.click(send()));
    expect(update.mock.calls[2][1].amount).toBe(3_000_000);
    expect(submit).toHaveBeenCalledTimes(1);
  });

  it('leaves the last confirmed snapshot intact if a newer write fails', async () => {
    update.mockResolvedValueOnce({ ...initial, amount: 2_000_000 }).mockRejectedValueOnce(new Error('Последняя запись не прошла'));
    mount(); changeAmount('2000000'); await tick();
    changeAmount('3000000'); await tick();
    expect(screen.getByText('Не сохранено')).toBeInTheDocument();
    expect(amount()).toHaveValue('3\u00a0000\u00a0000');
    expect(submit).not.toHaveBeenCalled();
    update.mockRejectedValueOnce(new Error('Повтор не прошёл'));
    await act(async () => fireEvent.click(send()));
    expect(update.mock.calls[2][1].amount).toBe(3_000_000);
    expect(submit).not.toHaveBeenCalled();
  });

  it('does not continue queued saves or callbacks after the card unmounts', async () => {
    const first = deferred<Application>(); update.mockReturnValue(first.promise);
    const ui = mount(); changeAmount('2000000'); await tick(); changeAmount('3000000');
    ui.unmount(); await act(async () => first.resolve(initial));
    await tick(); expect(update).toHaveBeenCalledTimes(1); expect(ui.saved).not.toHaveBeenCalled();
  });

  it('does not submit a saved revision with server validation problems', async () => {
    update.mockResolvedValueOnce({ ...initial, amount: 0, submissionProblems: ['Укажите сумму'] });
    mount(); changeAmount(''); await act(async () => fireEvent.click(send()));
    expect(screen.getByText('Укажите сумму')).toBeInTheDocument();
    expect(submit).not.toHaveBeenCalled(); expect(amount()).not.toBeDisabled();
  });

  it('uses refreshed document validation for an unchanged confirmed form', async () => {
    const saved = vi.fn(); const submitted = vi.fn(); submit.mockResolvedValue(initial);
    const ui = render(<DraftForm application={{ ...initial, submissionProblems: ['Загрузите документ'] }} onSaved={saved} onSubmitted={submitted} />);
    expect(send()).toBeDisabled();
    ui.rerender(<DraftForm application={initial} onSaved={saved} onSubmitted={submitted} />);
    await act(async () => fireEvent.click(send()));
    expect(update).not.toHaveBeenCalled(); expect(submit).toHaveBeenCalledTimes(1);
    expect(submitted).toHaveBeenCalledTimes(1);
  });
});
