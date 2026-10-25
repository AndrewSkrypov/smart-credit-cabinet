import { act, fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AdviceBlock } from './AdviceBlock';

const { advice, summarize } = vi.hoisted(() => ({ advice: vi.fn(), summarize: vi.fn() }));
vi.mock('../../../api/client', () => ({ underwritingApi: { advice }, assistantApi: { underwritingSummary: summarize } }));

function deferred<T>() {
  let resolve!: (value: T) => void; let reject!: (error: Error) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}
const answer = (text: string) => ({ mode: 'RULES' as const, text, sources: [], link: null });
const snapshot = (version: string) => ({ engine: 'Сеть правил', verdict: `Свежие рекомендации: ${version}`, rulesFired: 1, rulesTotal: 2,
  items: [{ code: 'CURRENT', kind: 'INFO', title: 'Актуальное состояние', text: version, because: [] }] });
const initial = { applicationId: 'a', version: 'MANUAL_REVIEW' };
const contexts = ['INFO_REQUESTED', 'REJECTED', 'DISBURSED'].map(version => ({ applicationId: 'a', version }))
  .concat({ applicationId: 'b', version: 'MANUAL_REVIEW' });

beforeEach(() => { advice.mockReset(); summarize.mockReset(); });

describe('advice availability', () => {
  it('distinguishes loading, request failure with retry, and a valid empty response', async () => {
    let reject!: (error: Error) => void;
    advice.mockReturnValueOnce(new Promise((_yes, no) => { reject = no; }));
    render(<AdviceBlock applicationId="a" version="1" />);
    expect(screen.getByRole('status')).toHaveTextContent('Загружаем рекомендации');
    await act(async () => reject(new Error('Источник рекомендаций недоступен')));
    expect(screen.getByRole('alert')).toHaveTextContent('Источник рекомендаций недоступен');
    advice.mockResolvedValueOnce({ engine: 'Сеть правил', items: [], rulesFired: 0, rulesTotal: 1, verdict: 'Заявка закрыта; новые условия не рассчитываются.' });
    await act(async () => fireEvent.click(screen.getByRole('button', { name: 'Повторить' })));
    expect(screen.getByText('Заявка закрыта; новые условия не рассчитываются.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Повторить' })).not.toBeInTheDocument();
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });
});

describe('summary snapshot isolation', () => {
  it.each(contexts)('drops a completed old summary on $applicationId/$version and permits a fresh one', async next => {
    advice.mockResolvedValueOnce(snapshot(initial.version)).mockResolvedValueOnce(snapshot(next.version));
    summarize.mockResolvedValueOnce(answer('Старый пересказ о другом сроке')).mockResolvedValueOnce(answer('Новый пересказ текущих рекомендаций'));
    const view = render(<AdviceBlock {...initial} />);
    fireEvent.click(await screen.findByRole('button', { name: 'Кратко словами' }));
    await screen.findByText('Старый пересказ о другом сроке');
    view.rerender(<AdviceBlock {...next} />);
    await screen.findByText(`Свежие рекомендации: ${next.version}`);
    expect(screen.queryByText('Старый пересказ о другом сроке')).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Кратко словами' }));
    expect(await screen.findByText('Новый пересказ текущих рекомендаций')).toBeInTheDocument();
    expect(summarize).toHaveBeenLastCalledWith(next.applicationId);
  });

  it.each(contexts)('ignores late success and finally on $applicationId/$version while its new summary is pending', async next => {
    const old = deferred<ReturnType<typeof answer>>(); const fresh = deferred<ReturnType<typeof answer>>();
    advice.mockResolvedValueOnce(snapshot(initial.version)).mockResolvedValueOnce(snapshot(next.version));
    summarize.mockReturnValueOnce(old.promise).mockReturnValueOnce(fresh.promise);
    const view = render(<AdviceBlock {...initial} />);
    fireEvent.click(await screen.findByRole('button', { name: 'Кратко словами' }));
    view.rerender(<AdviceBlock {...next} />);
    await screen.findByText(`Свежие рекомендации: ${next.version}`);
    const retry = screen.getByRole('button', { name: 'Кратко словами' });
    expect(retry).toBeEnabled(); fireEvent.click(retry);
    await act(async () => old.resolve(answer('Запоздалый старый пересказ')));
    expect(screen.queryByText('Запоздалый старый пересказ')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Готовлю сводку…' })).toBeDisabled();
    await act(async () => fresh.resolve(answer('Актуальный новый пересказ')));
    expect(screen.getByText('Актуальный новый пересказ')).toBeInTheDocument();
    expect(summarize.mock.calls.map(call => call[0])).toEqual(['a', next.applicationId]);
  });

  it('ignores an old rejection and its finally without unlocking a newer request', async () => {
    const old = deferred<ReturnType<typeof answer>>(); const fresh = deferred<ReturnType<typeof answer>>();
    advice.mockResolvedValueOnce(snapshot(initial.version)).mockResolvedValueOnce(snapshot('DISBURSED'));
    summarize.mockReturnValueOnce(old.promise).mockReturnValueOnce(fresh.promise);
    const view = render(<AdviceBlock {...initial} />);
    fireEvent.click(await screen.findByRole('button', { name: 'Кратко словами' }));
    view.rerender(<AdviceBlock applicationId="a" version="DISBURSED" />);
    fireEvent.click(await screen.findByRole('button', { name: 'Кратко словами' }));
    await act(async () => old.reject(new Error('Старая ошибка пересказа')));
    expect(screen.queryByText('Старая ошибка пересказа')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Готовлю сводку…' })).toBeDisabled();
    await act(async () => fresh.resolve(answer('Кредит уже выдан')));
    expect(screen.getByText('Кредит уже выдан')).toBeInTheDocument();
  });

  it('clears an already displayed error when the status changes and allows retry for the new snapshot', async () => {
    advice.mockResolvedValueOnce(snapshot(initial.version)).mockResolvedValueOnce(snapshot('INFO_REQUESTED'));
    summarize.mockRejectedValueOnce(new Error('Ошибка прежней версии')).mockResolvedValueOnce(answer('Нужны документы'));
    const view = render(<AdviceBlock {...initial} />);
    fireEvent.click(await screen.findByRole('button', { name: 'Кратко словами' }));
    await screen.findByText('Ошибка прежней версии');
    view.rerender(<AdviceBlock applicationId="a" version="INFO_REQUESTED" />);
    await screen.findByText('Свежие рекомендации: INFO_REQUESTED');
    expect(screen.queryByText('Ошибка прежней версии')).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Кратко словами' }));
    expect(await screen.findByText('Нужны документы')).toBeInTheDocument();
  });
});
