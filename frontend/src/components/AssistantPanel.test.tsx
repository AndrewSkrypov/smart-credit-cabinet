import { act, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, MemoryRouter, RouterProvider } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AssistantPanel } from './AssistantPanel';
import { AssistantAnswer } from '../types';
import { assistantApi } from '../api/client';

const ask = vi.fn().mockResolvedValue({ text: 'Банк просит <b>декларацию</b>.', mode: 'RULES', sources: ['Заявка № 1042'] });

vi.mock('../api/client', () => ({
  assistantApi: {
    status: vi.fn().mockResolvedValue({ mode: 'RULES', model: null }),
    ask: (...args: unknown[]) => ask(...args)
  }
}));

const APPLICATION = '6f1d4c1e-0b8e-4c55-9a51-000000000001';

describe('AssistantPanel', () => {
  beforeEach(() => {
    ask.mockReset().mockResolvedValue({ text: 'Банк просит <b>декларацию</b>.', mode: 'RULES', sources: ['Заявка № 1042'] });
    vi.mocked(assistantApi.status).mockResolvedValue({ mode: 'RULES', model: null });
  });
  it('asks about the application on screen and shows the answer as plain text', async () => {
    render(
      <MemoryRouter initialEntries={[`/client/applications/${APPLICATION}`]}>
        <AssistantPanel />
      </MemoryRouter>
    );
    await userEvent.click(screen.getByRole('button', { name: 'Открыть помощника' }));
    expect(await screen.findByText(/Справочный режим/)).toBeInTheDocument();
    await userEvent.click(screen.getByText('Какие документы нужно загрузить?'));
    expect(ask).toHaveBeenCalledWith('Какие документы нужно загрузить?', APPLICATION, []);
    expect(await screen.findByText('Банк просит <b>декларацию</b>.')).toBeInTheDocument();
    expect(screen.getByText('Источники: Заявка № 1042')).toBeInTheDocument();
  });

  it('does not append a late answer from a previous application to the new conversation', async () => {
    let resolveLate!: (answer: AssistantAnswer) => void;
    ask.mockReturnValueOnce(new Promise<AssistantAnswer>((resolve) => { resolveLate = resolve; }));
    const second = '6f1d4c1e-0b8e-4c55-9a51-000000000002';
    const router = createMemoryRouter([{ path: '*', element: <AssistantPanel /> }], { initialEntries: [`/client/applications/${APPLICATION}`] });
    render(<RouterProvider router={router} />);
    await userEvent.click(screen.getByRole('button', { name: 'Открыть помощника' }));
    await userEvent.click(screen.getByText('Какие документы нужно загрузить?'));
    expect(ask).toHaveBeenCalledWith('Какие документы нужно загрузить?', APPLICATION, []);
    await act(async () => { await router.navigate(`/client/applications/${second}`); });
    expect(screen.queryByText('Помощник печатает…')).not.toBeInTheDocument();
    await act(async () => { resolveLate({ text: 'Ответ только про первую заявку', mode: 'RULES', sources: ['Первая заявка'] }); });
    expect(screen.queryByText('Ответ только про первую заявку')).not.toBeInTheDocument();
    expect(screen.queryByText('Источники: Первая заявка')).not.toBeInTheDocument();
    await userEvent.type(screen.getByLabelText('Ваш вопрос'), 'Что со второй заявкой?');
    await userEvent.click(screen.getByRole('button', { name: 'Спросить' }));
    expect(ask).toHaveBeenLastCalledWith('Что со второй заявкой?', second, []);
  });

  it('labels the actual answer mode even when a model is available', async () => {
    vi.mocked(assistantApi.status).mockResolvedValue({ mode: 'MODEL', model: 'synthetic-model' });
    render(<MemoryRouter><AssistantPanel /></MemoryRouter>);
    await userEvent.click(screen.getByRole('button', { name: 'Открыть помощника' }));
    expect(await screen.findByText(/Доступна модель.*synthetic-model/)).toBeInTheDocument();
    await userEvent.click(screen.getByText('Как считается платёж?'));
    expect(await screen.findByText('По правилам и данным кабинета')).toBeInTheDocument();
    expect(screen.queryByText('Ответ модели')).not.toBeInTheDocument();
    ask.mockResolvedValueOnce({ text: 'Модель пояснила термин.', mode: 'MODEL', sources: ['Справка'] });
    await userEvent.type(screen.getByLabelText('Ваш вопрос'), 'Объясни термин');
    await userEvent.click(screen.getByRole('button', { name: 'Спросить' }));
    expect(await screen.findByText('Ответ модели')).toBeInTheDocument();
  });

  it('ignores an old error and keeps the next application request busy until its own response', async () => {
    let rejectOld!: (reason: Error) => void;
    let resolveNew!: (answer: AssistantAnswer) => void;
    ask.mockReturnValueOnce(new Promise((_, reject) => { rejectOld = reject; }))
      .mockReturnValueOnce(new Promise<AssistantAnswer>((resolve) => { resolveNew = resolve; }));
    const second = '6f1d4c1e-0b8e-4c55-9a51-000000000002';
    const router = createMemoryRouter([{ path: '*', element: <AssistantPanel /> }], { initialEntries: [`/client/applications/${APPLICATION}`] });
    render(<RouterProvider router={router} />);
    await userEvent.click(screen.getByRole('button', { name: 'Открыть помощника' }));
    await userEvent.click(screen.getByText('Какие документы нужно загрузить?'));
    await act(async () => { await router.navigate(`/client/applications/${second}`); });
    await userEvent.type(screen.getByLabelText('Ваш вопрос'), 'Расскажи про вторую заявку');
    await userEvent.click(screen.getByRole('button', { name: 'Спросить' }));
    await act(async () => { rejectOld(new Error('Ошибка по первой заявке')); });
    expect(screen.queryByText('Ошибка по первой заявке')).not.toBeInTheDocument();
    expect(screen.getByText('Помощник печатает…')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Спросить' })).toBeDisabled();
    await act(async () => { resolveNew({ text: 'Ответ по второй заявке', mode: 'RULES', sources: ['Вторая заявка'] }); });
    expect(await screen.findByText('Ответ по второй заявке')).toBeInTheDocument();
    expect(screen.queryByText('Помощник печатает…')).not.toBeInTheDocument();
  });
});
