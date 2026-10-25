import { act, fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { AdviceBlock } from './AdviceBlock';

const { advice } = vi.hoisted(() => ({ advice: vi.fn() }));
vi.mock('../../../api/client', () => ({ underwritingApi: { advice } }));

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
