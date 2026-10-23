import { act, cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ChatMessage, ChatPage } from '../types';
import { MessagesBlock } from './MessagesBlock';

const api = vi.hoisted(() => ({ page: vi.fn(), send: vi.fn(), read: vi.fn(), upload: vi.fn(), audit: vi.fn() }));
vi.mock('../api/client', () => ({ chatApi: api, applicationsApi: { downloadDocument: vi.fn() } }));
vi.mock('../auth/AuthProvider', () => ({ useAuth: () => ({ session: { user: { role: 'CLIENT' } } }) }));

const message = (seq: number, text = `Сообщение ${seq}`): ChatMessage => ({ id: `m${seq}`, seq, authorName: 'Сотрудник банка', side: 'BANK', text, createdAt: '2026-10-22T10:00:00Z', attachments: [] });
const page = (messages: ChatMessage[], lastSeq: number, hasNewer = false): ChatPage => ({ messages, lastSeq, readSeq: 0, unreadCount: lastSeq, side: 'COMPANY', writable: true, readOnlyReason: null, hasOlder: false, hasNewer });
let notifyVisible: (visible: boolean) => void;

beforeEach(() => {
  vi.stubGlobal('IntersectionObserver', class {
    constructor(callback: IntersectionObserverCallback) {
      notifyVisible = (visible) => callback([{ isIntersecting: visible } as IntersectionObserverEntry], {} as IntersectionObserver);
    }
    observe() {}
    disconnect() {}
  });
  api.read.mockImplementation(async (_id: string, seq: number) => ({ lastSeq: seq, readSeq: seq, unreadCount: 0 }));
});
afterEach(() => { cleanup(); vi.useRealTimers(); vi.unstubAllGlobals(); vi.resetAllMocks(); });

describe('MessagesBlock', () => {
  it('fetches every page of an accumulated backlog and acknowledges only when its end is actually visible', async () => {
    vi.useFakeTimers();
    api.page.mockResolvedValueOnce(page([message(1, '<b>План</b> — простой текст')], 1))
      .mockResolvedValueOnce(page(Array.from({ length: 50 }, (_, i) => message(i + 2)), 100, true))
      .mockResolvedValueOnce(page(Array.from({ length: 49 }, (_, i) => message(i + 52)), 100));
    await act(async () => { render(<MessagesBlock applicationId="app" status="MANUAL_REVIEW" />); });
    expect(screen.getByText('<b>План</b> — простой текст')).toBeInTheDocument();
    expect(document.querySelector('b')).toBeNull();
    expect(api.read).not.toHaveBeenCalled();
    await act(async () => { await vi.advanceTimersByTimeAsync(5000); });
    expect(api.page).toHaveBeenNthCalledWith(2, 'app', { after: 1 });
    expect(api.read).not.toHaveBeenCalled();
    await act(async () => { await vi.advanceTimersByTimeAsync(5000); });
    expect(api.page).toHaveBeenNthCalledWith(3, 'app', { after: 51 });
    expect(within(screen.getByRole('list', { name: 'Сообщения участников заявки' })).getAllByRole('listitem')).toHaveLength(100);
    expect(api.read).not.toHaveBeenCalled();
    await act(async () => { notifyVisible(true); });
    expect(api.read).toHaveBeenCalledExactlyOnceWith('app', 100);
  });

  it('a retry after an uncertain send keeps the same key and renders the committed reply only once', async () => {
    api.page.mockResolvedValueOnce(page([message(1)], 1)).mockResolvedValue(page([message(2, 'Новый план')], 2));
    api.send.mockRejectedValueOnce(new Error('Ответ не получен. Повторите отправку.')).mockResolvedValueOnce(message(2, 'Новый план'));
    render(<MessagesBlock applicationId="app" status="MANUAL_REVIEW" />);
    await screen.findByText('Сообщение 1');
    const user = userEvent.setup();
    await user.type(screen.getByRole('textbox', { name: 'Сообщение' }), 'Новый план');
    await user.click(screen.getByRole('button', { name: 'Отправить сообщение' }));
    await screen.findByText('Ответ не получен. Повторите отправку.');
    await user.click(screen.getByRole('button', { name: 'Отправить сообщение' }));
    await waitFor(() => expect(api.send).toHaveBeenCalledTimes(2));
    expect(api.send.mock.calls[1]).toEqual(api.send.mock.calls[0]);
    const list = screen.getByRole('list', { name: 'Сообщения участников заявки' });
    expect(within(list).getAllByText('Новый план')).toHaveLength(1);
    expect(api.read).not.toHaveBeenCalled();
  });
});
