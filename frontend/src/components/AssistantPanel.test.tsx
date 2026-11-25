import { act, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, MemoryRouter, RouterProvider } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AssistantPanel } from './AssistantPanel';
import { AssistantAnswer } from '../types';
import { assistantApi } from '../api/client';
import { readSession } from '../auth/session';

const ask = vi.fn().mockResolvedValue({ text: 'Банк просит <b>декларацию</b>.', mode: 'RULES', sources: ['Заявка № 1042'] });
const get = vi.fn();
const post = vi.fn();

vi.mock('../auth/session', () => ({ readSession: vi.fn() }));

vi.mock('../api/client', () => ({
  get: (...args: unknown[]) => get(...args),
  post: (...args: unknown[]) => post(...args),
  api: { post: (...args: unknown[]) => post(...args) },
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
    vi.mocked(readSession).mockReturnValue(null);
    get.mockReset().mockResolvedValue([]);
    post.mockReset().mockResolvedValue(undefined);
  });

  it('shows the proactive agent card and requires explicit confirmation before sending', async () => {
    const user = userEvent.setup();
    vi.mocked(readSession).mockReturnValue({ token: 'synthetic' } as ReturnType<typeof readSession>);
    get.mockResolvedValue([{ type: 'INFO_DEADLINE', title: 'Банк ждёт документы', detail: 'Выписка', applicationId: APPLICATION, canRemind: true }]);
    render(<MemoryRouter initialEntries={['/agent']}><AssistantPanel /></MemoryRouter>);
    await user.click(screen.getByRole('button', { name: 'Открыть помощника' }));
    await user.click(await screen.findByRole('button', { name: 'Напомнить клиенту' }));
    expect(post).not.toHaveBeenCalled();
    await user.click(screen.getByRole('button', { name: 'Отправить уведомление' }));
    expect(post).toHaveBeenCalledOnce();
    expect(post).toHaveBeenCalledWith('/assistant/reminders', { applicationId: APPLICATION });
    expect(await screen.findByText('Клиенту отправлено уведомление')).toBeInTheDocument();
  });

  it('shows the selected draft terms before calling the agent creation endpoint', async () => {
    const user = userEvent.setup();
    vi.mocked(readSession).mockReturnValue({ token: 'synthetic' } as ReturnType<typeof readSession>);
    ask.mockResolvedValue({ text: 'Подходит оборотный кредит', mode: 'RULES', sources: [],
      link: { label: 'Оформить', path: '/client/new?purpose=WORKING_CAPITAL&amount=1000000&termMonths=12' } });
    post.mockResolvedValueOnce([{ fits: true, termMonths: 12, product: { code: 'WORKING_CAPITAL', name: 'Оборотный кредит' } }])
      .mockResolvedValueOnce({ id: APPLICATION });
    render(<MemoryRouter initialEntries={[`/agent/clients/${APPLICATION}`]}><AssistantPanel /></MemoryRouter>);
    await user.click(screen.getByRole('button', { name: 'Открыть помощника' }));
    await user.type(screen.getByLabelText('Ваш вопрос'), 'Подбери кредит на закупку');
    await user.click(screen.getByRole('button', { name: 'Спросить' }));
    await user.click(await screen.findByRole('button', { name: 'Подготовить черновик' }));
    await screen.findByRole('dialog', { name: 'Создать черновик для клиента?' });
    expect(post.mock.calls.map(([path]) => path)).toEqual(['/products/match']);
    await user.click(screen.getByRole('button', { name: /^Создать черновик$/ }));
    expect(post).toHaveBeenLastCalledWith('/agent/applications', {
      companyId: APPLICATION, productCode: 'WORKING_CAPITAL', purpose: 'WORKING_CAPITAL', amount: 1_000_000, termMonths: 12
    });
  });
  it('keeps twelve questions visible but sends only four previous completed turns, bounded to MAX_TEXT', async () => {
    const user = userEvent.setup();
    ask.mockImplementation((question: string) => Promise.resolve({ text: `Ответ ${question}` + 'а'.repeat(1050), mode: 'RULES', sources: [] }));
    render(<MemoryRouter><AssistantPanel /></MemoryRouter>);
    await user.click(screen.getByRole('button', { name: 'Открыть помощника' }));
    for (let i = 1; i <= 12; i++) {
      await user.click(screen.getByLabelText('Ваш вопрос'));
      await user.paste(`Вопрос ${i}`);
      await user.click(screen.getByRole('button', { name: 'Спросить' }));
      await screen.findByText(`Ответ Вопрос ${i}` + 'а'.repeat(1050));
      const [question, appId, history] = ask.mock.calls[i - 1];
      expect(question).toBe(`Вопрос ${i}`); expect(appId).toBeNull();
      expect(history).toHaveLength(Math.min((i - 1) * 2, 4));
      expect(history.every((turn: { text: string }) => turn.text.length <= 1000 && turn.text !== question)).toBe(true);
      if (i > 2) expect(history.map((turn: { role: string }) => turn.role)).toEqual(['user', 'assistant', 'user', 'assistant']);
    }
    expect(screen.getByText('Вопрос 1', { exact: true })).toBeInTheDocument();
    expect(ask).toHaveBeenCalledTimes(12);
  }, 20000);

  it('retries a failed question without duplicating it or inventing a completed turn', async () => {
    const user = userEvent.setup();
    ask.mockRejectedValueOnce(new Error('Сеть недоступна'));
    render(<MemoryRouter><AssistantPanel /></MemoryRouter>);
    await user.click(screen.getByRole('button', { name: 'Открыть помощника' }));
    await user.click(screen.getByText('Как считается платёж?'));
    await screen.findByText('Сеть недоступна');
    await user.click(screen.getByRole('button', { name: 'Повторить вопрос' }));
    await screen.findByText('Банк просит <b>декларацию</b>.');
    expect(ask).toHaveBeenLastCalledWith('Как считается платёж?', null, []);
    expect(screen.getAllByText('Как считается платёж?', { exact: true })).toHaveLength(1);
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
