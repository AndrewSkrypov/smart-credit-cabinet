import { ThemeProvider } from '@mui/material';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api } from '../../api/client';
import { FinancingInputs, FinancingScenario, InvoiceExtraction } from '../../api/financing';
import { AuthProvider } from '../../auth/AuthProvider';
import { readSession, writeSession } from '../../auth/session';
import { AppRoutes } from '../../routes/AppRoutes';
import { cabinetTheme } from '../../theme/theme';
import { Application, ChatPage, Role } from '../../types';

const FIRST_APP = '6f1d4c1e-0b8e-4c55-9a51-000000000001';
const SECOND_APP = '6f1d4c1e-0b8e-4c55-9a51-000000000002';
const flagUrl = '/assistant/ui-config';
const appUrl = (id: string) => `/agent/applications/${id}`;
const delayLabel = 'Задержка поступления, дней';
const applyLabel = 'Подставить 2 дня';
const sendLabel = 'Отправить клиенту на подтверждение';
const hintLabel = 'Подсказка Кеши';
const invoice: InvoiceExtraction = {
  mode: 'MANUAL', amount: { value: 140_000, page: 1, quote: 'К оплате 140 000 руб.' },
  dates: [{ value: '2026-12-10', page: 1, quote: 'Срок оплаты 10.12.2026' }], reason: null
};
const chat: ChatPage = {
  messages: [], lastSeq: 0, readSeq: 0, unreadCount: 0, side: 'COMPANY', writable: false,
  readOnlyReason: null, hasOlder: false, hasNewer: false
};
const rect = { x: 260, y: 360, top: 360, right: 580, bottom: 420, left: 260, width: 320, height: 60, toJSON: () => ({}) };
const observed = new Map<Element, IntersectionObserverCallback>();
const delay = () => screen.getByRole('spinbutton', { name: delayLabel });
const launcher = () => screen.getByRole('button', { name: 'Открыть помощника' });
const send = () => screen.getByRole('button', { name: sendLabel });

function application(id: string, editable = true): Application {
  return {
    id, number: id === FIRST_APP ? 1042 : 2042, status: 'DRAFT', closeReason: null,
    company: { id: 'company', inn: '7701234567', name: 'ООО Синтетика', legalForm: 'LEGAL_ENTITY' },
    product: { code: 'WORKING_CAPITAL', name: 'Оборотный кредит', revolving: false },
    purpose: 'WORKING_CAPITAL', purposeTitle: 'Оборотные средства', purposeComment: null,
    amount: 2_000_000, termMonths: 12, declaredRevenue12m: 5_000_000, declaredMonthlyDebtPayments: 0,
    bureauConsent: false, createdAt: '2026-11-28T16:00:00Z', submittedAt: null, statusChangedAt: '2026-11-28T16:00:00Z',
    version: 1, allowedActions: ['REQUEST_CLIENT_CONFIRMATION', 'AGENT_CANCEL'], editable,
    canUploadDocuments: true, submissionProblems: [], requiredDocuments: [], documents: [], estimate: null,
    process: { infoRequest: null, offer: null, signing: null, loan: null, outcome: null }
  };
}

function scenario(delayDays: number, inputs?: FinancingInputs): FinancingScenario {
  return {
    revision: 1, status: 'DRAFT', loanAmount: 2_000_000, annualRatePercent: 20, options: [],
    inputs: inputs ?? {
      openingBalance: 150_000, expectedReceiptAmount: 140_000, expectedReceiptDate: '2026-12-10', paymentDate: '2026-12-15',
      delayDays, alternativeTermMonths: 24, selectedTermMonths: 12
    }
  };
}

function renderCabinet({ flag = true, initialDelay = null, role = 'AGENT', editable = true, actorId = 'synthetic-user' }: {
  flag?: boolean | 'unavailable'; initialDelay?: number | 'unavailable' | null; role?: Role; editable?: boolean; actorId?: string;
} = {}) {
  writeSession({ token: `session-${actorId}`, user: {
    id: actorId, fullName: 'Тестовый Пользователь', email: 'test@example.test', role, status: 'ACTIVE', companyId: null, canSign: false
  } });
  let configuredFlag = flag;
  const requests: { method: string; url: string; data: unknown }[] = [];
  api.defaults.adapter = async (config) => {
    const method = config.method ?? 'get';
    const url = config.url ?? '';
    requests.push({ method, url, data: config.data });
    const reply = (data: unknown, status = 200) => ({ config, data, status, statusText: 'OK', headers: {} });
    const applicationRoute = url.match(/^\/agent\/applications\/([^/]+)(\/.*)?$/);
    if (applicationRoute) {
      const [, id, subroute] = applicationRoute;
      if (method === 'get' && !subroute) return reply(application(id, editable));
      if (method === 'get' && subroute === '/financing-scenario') {
        const savedDelay = id === FIRST_APP ? initialDelay : 2;
        if (savedDelay === 'unavailable') throw new Error('Financing scenario unavailable');
        if (savedDelay === null) return reply(null, 204);
        return reply(scenario(savedDelay));
      }
      if (method === 'get' && subroute === '/events') return reply([]);
      if (method === 'get' && subroute === '/messages') return reply(chat);
      if (method === 'post' && subroute === '/invoice-extraction') return reply(invoice);
      if (method === 'post' && subroute === '/documents') return reply({ id: 'invoice-document' });
      if (method === 'put' && !subroute) return reply(application(id, editable));
      if (method === 'put' && subroute === '/financing-scenario') return reply(scenario(0, JSON.parse(config.data)));
    }
    if (url === flagUrl) {
      if (configuredFlag === 'unavailable') throw new Error('Configuration unavailable');
      return reply({ keshaEnabled: configuredFlag });
    }
    if (url === '/notifications/unread-count') return reply({ unread: 0 });
    if (url === '/notifications/settings') return reply({ emailEnabled: false, version: 1 });
    if (url === '/assistant/status') return reply({ mode: 'RULES', model: null });
    if (url === '/products' || url === '/assistant/next-steps') return reply([]);
    throw new Error(`Unexpected request ${method} ${url}`);
  };
  const router = createMemoryRouter([{ path: '*', element: <AuthProvider><AppRoutes /></AuthProvider> }], {
    initialEntries: [appUrl(FIRST_APP)], future: { v7_relativeSplatPath: true }
  });
  const { unmount } = render(<ThemeProvider theme={cabinetTheme}><RouterProvider router={router} future={{ v7_startTransition: true }} /></ThemeProvider>);
  return { requests, router, unmount, disableFlag: () => { configuredFlag = false; }, mutations: () => requests.filter(({ method }) => method !== 'get') };
}

async function ready() {
  await waitFor(() => expect(delay()).toBeEnabled(), { timeout: 3_000 });
}

async function setTargetVisible(visible: boolean, requireObserver = visible) {
  const target = screen.getByTestId('kesha-delay-target');
  if (requireObserver) await waitFor(() => expect(observed.has(target)).toBe(true), { timeout: 3_000 });
  const callback = observed.get(target);
  await act(async () => callback?.([{
    target, isIntersecting: visible, intersectionRatio: visible ? 1 : 0,
    boundingClientRect: rect, intersectionRect: rect, rootBounds: null, time: 0
  }], {} as IntersectionObserver));
}

async function revealOffer() {
  await setTargetVisible(true);
  return screen.findByLabelText(hintLabel);
}

const realAdapter = api.defaults.adapter;

beforeEach(() => {
  vi.stubGlobal('IntersectionObserver', class {
    #targets = new Set<Element>();
    constructor(private callback: IntersectionObserverCallback) {}
    observe(target: Element) { this.#targets.add(target); observed.set(target, this.callback); }
    unobserve(target: Element) { this.#targets.delete(target); observed.delete(target); }
    disconnect() { this.#targets.forEach((target) => this.unobserve(target)); }
  });
  vi.stubGlobal('matchMedia', (media: string) => ({
    media, matches: media === '(prefers-reduced-motion: reduce)', addListener: vi.fn(), removeListener: vi.fn(),
    addEventListener: vi.fn(), removeEventListener: vi.fn()
  }));
  vi.spyOn(Element.prototype, 'getBoundingClientRect').mockReturnValue(rect);
  vi.spyOn(window, 'scrollTo').mockImplementation(() => undefined);
});

afterEach(() => {
  cleanup();
  api.defaults.adapter = realAdapter;
  observed.clear();
  localStorage.clear();
  sessionStorage.clear();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe('Kesha on the agent application route', () => {
  it.each([0, 5])('substitutes 2 for %i with consent and restores the original draft on undo', async (initialDelay) => {
    const { mutations } = renderCabinet({ initialDelay });
    await ready();
    const file = new File(['synthetic'], 'invoice.pdf', { type: 'application/pdf' });
    if (initialDelay === 5) {
      fireEvent.change(screen.getByLabelText('Счёт PDF для примерки'), { target: { files: [file] } });
      fireEvent.click(await screen.findByRole('button', { name: 'Использовать сумму из счёта' }));
      fireEvent.click(screen.getByRole('radio', { name: /Срок оплаты/ }));
      fireEvent.click(screen.getByRole('radio', { name: '24 месяца' }));
      expect(send()).toBeDisabled();
    } else {
      expect(send()).toBeEnabled();
    }
    const initialMutations = mutations();
    const existingStorage = [Object.entries(localStorage), Object.entries(sessionStorage)];
    const otherField = screen.getByLabelText('Дата платежа');
    const selectedTerm = screen.getByRole('radio', { name: initialDelay === 5 ? '24 месяца' : '12 месяцев' });
    expect(screen.queryByRole('button', { name: applyLabel })).not.toBeInTheDocument();
    const offer = await revealOffer();
    expect(offer).toHaveTextContent('А если деньги от покупателя поступят на два дня позже?');
    if (initialDelay === 5) expect(offer).toHaveTextContent(/с 5 на 2 дня/);
    expect(delay()).toHaveValue(initialDelay);
    expect(window.scrollTo).not.toHaveBeenCalled();
    expect(mutations()).toEqual(initialMutations);

    const apply = within(offer).getByRole('button', { name: applyLabel });
    const user = userEvent.setup();
    act(() => apply.focus());
    await user.keyboard('{Enter}');
    fireEvent.click(apply);
    expect(delay()).toHaveValue(2);
    expect(otherField).toHaveValue('2026-12-15');
    expect(selectedTerm).toBeChecked();
    expect(send()).toBeDisabled();
    expect(screen.getByRole('heading', { name: 'Заявка № 1042' })).toBeVisible();
    expect(mutations()).toEqual(initialMutations);

    expect(offer).toHaveTextContent(/Подставил 2 дня.*Расчёт ещё не обновлён/);
    const undo = screen.getByRole('button', { name: 'Отменить' });
    expect(undo).toHaveFocus();
    await user.keyboard('{Enter}');
    expect(delay()).toHaveValue(initialDelay);
    expect(otherField).toHaveValue('2026-12-15');
    expect(selectedTerm).toBeChecked();
    expect(mutations()).toEqual(initialMutations);
    expect([Object.entries(localStorage), Object.entries(sessionStorage)]).toEqual(existingStorage);
    if (initialDelay === 0) {
      expect(send()).toBeEnabled();
      return;
    }

    expect(send()).toBeDisabled();
    expect(screen.getByRole('radio', { name: /Срок оплаты/ })).toBeChecked();
    await user.click(screen.getByRole('button', { name: 'Рассчитать и сохранить варианты' }));
    await waitFor(() => expect(mutations()).toHaveLength(4));
    expect(mutations().map(({ method, url }) => [method, url])).toEqual([
      ['post', `${appUrl(FIRST_APP)}/invoice-extraction`], ['put', appUrl(FIRST_APP)],
      ['post', `${appUrl(FIRST_APP)}/documents`], ['put', `${appUrl(FIRST_APP)}/financing-scenario`]
    ]);
    expect((mutations()[2].data as FormData).get('file')).toBe(file);
    expect(JSON.parse(mutations()[3].data as string)).toMatchObject({
      delayDays: 5, selectedTermMonths: 24, source: { documentId: 'invoice-document' }
    });
  }, 10_000);

  it.each(['Не сейчас', 'Escape'])('dismisses with %s without reopening on the same application', async (action) => {
    const { mutations } = renderCabinet();
    await ready();
    const offer = await revealOffer();
    const user = userEvent.setup();
    if (action === 'Escape') await user.keyboard('{Escape}');
    else await user.click(within(offer).getByRole('button', { name: action }));
    expect(screen.queryByLabelText(hintLabel)).not.toBeInTheDocument();
    await setTargetVisible(false);
    await setTargetVisible(true);
    expect(screen.queryByRole('button', { name: applyLabel })).not.toBeInTheDocument();
    expect(delay()).toHaveValue(0);
    expect(send()).toBeEnabled();
    expect(mutations()).toEqual([]);
  });

  it('keeps a manual edit when an obsolete undo is clicked', async () => {
    const { mutations } = renderCabinet();
    await ready();
    const offer = await revealOffer();
    fireEvent.click(within(offer).getByRole('button', { name: applyLabel }));
    const undo = screen.getByRole('button', { name: 'Отменить' });
    fireEvent.change(delay(), { target: { value: '3' } });
    fireEvent.click(undo);
    expect(delay()).toHaveValue(3);
    expect(send()).toBeDisabled();
    fireEvent.change(delay(), { target: { value: '0' } });
    expect(screen.queryByRole('button', { name: applyLabel })).not.toBeInTheDocument();
    expect(mutations()).toEqual([]);
  });

  it('invalidates the offer once its original delay has been manually edited and then restored', async () => {
    renderCabinet();
    await ready();
    const apply = within(await revealOffer()).getByRole('button', { name: applyLabel });
    fireEvent.change(delay(), { target: { value: '4' } });
    fireEvent.change(delay(), { target: { value: '0' } });
    fireEvent.click(apply);
    expect(delay()).toHaveValue(0);
    expect(screen.queryByRole('button', { name: applyLabel })).not.toBeInTheDocument();
  });

  it('invalidates an old application offer and avoids proposing 2 when the new application already has 2', async () => {
    const { router, mutations } = renderCabinet();
    await ready();
    const offer = await revealOffer();
    const oldApply = within(offer).getByRole('button', { name: applyLabel });
    await act(async () => router.navigate(appUrl(SECOND_APP)));
    await ready();
    await setTargetVisible(true);
    expect(screen.getByRole('heading', { name: 'Заявка № 2042' })).toBeVisible();
    expect(oldApply).not.toBeInTheDocument();
    fireEvent.click(oldApply);
    expect(delay()).toHaveValue(2);
    expect(send()).toBeEnabled();
    expect(screen.queryByRole('button', { name: applyLabel })).not.toBeInTheDocument();
    expect(mutations()).toEqual([]);
  });

  it('rejects a stale offer after the stored agent session changes', async () => {
    const { mutations } = renderCabinet();
    await ready();
    const apply = within(await revealOffer()).getByRole('button', { name: applyLabel });
    const originalSession = readSession();
    if (!originalSession) throw new Error('Expected the signed-in agent fixture');
    writeSession({ ...originalSession, token: 'another-token', user: { ...originalSession.user, id: 'another-agent' } });

    // A same-tab action may happen before the cross-tab storage event is processed.
    fireEvent.click(apply);
    expect(delay()).toHaveValue(0);
    await act(async () => window.dispatchEvent(new StorageEvent('storage')));
    expect(apply).not.toBeInTheDocument();
    expect(send()).toBeEnabled();
    expect(mutations()).toEqual([]);
  });

  it('waits while an input or the assistant chat has focus', async () => {
    const { mutations } = renderCabinet();
    await ready();
    const user = userEvent.setup();
    const field = screen.getByRole('textbox', { name: 'Комментарий к цели' });
    await user.click(field);
    await setTargetVisible(true);
    expect(field).toHaveFocus();
    expect(screen.queryByRole('button', { name: applyLabel })).not.toBeInTheDocument();
    await setTargetVisible(false);

    await user.click(launcher());
    expect(await screen.findByRole('textbox', { name: 'Ваш вопрос' })).toBeVisible();
    await setTargetVisible(true);
    expect(screen.queryByLabelText(hintLabel)).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Закрыть помощника' }));
    await waitFor(() => expect(screen.queryByRole('textbox', { name: 'Ваш вопрос' })).not.toBeInTheDocument());
    await user.click(screen.getByRole('heading', { name: 'Примерка кредита' }));
    await setTargetVisible(true);
    expect(await screen.findByRole('button', { name: applyLabel }, { timeout: 3_000 })).toBeVisible();
    expect(mutations()).toEqual([]);
  }, 10_000);

  it('keeps a modal on top of the offer and blocks its action until the modal closes', async () => {
    const { mutations } = renderCabinet();
    await ready();
    const user = userEvent.setup();
    const offer = await revealOffer();
    const apply = within(offer).getByRole('button', { name: applyLabel });
    await user.click(screen.getByRole('button', { name: 'Отменить черновик' }));
    expect(await screen.findByRole('dialog', { name: 'Отменить черновик заявки?' })).toBeVisible();
    await waitFor(() => expect(offer).not.toBeVisible());
    fireEvent.click(apply);
    expect(screen.getByLabelText(delayLabel)).toHaveValue(0);
    await user.click(screen.getByRole('button', { name: 'Не отменять' }));
    await waitFor(() => expect(offer).toBeVisible());
    expect(mutations()).toEqual([]);
  }, 10_000);

  it('renders Kesha in a fixed portal, opens the ordinary chat and restores the dock after scrolling away', async () => {
    renderCabinet();
    await ready();
    const dock = await screen.findByTestId('kesha-dock');
    const overlay = screen.getByTestId('kesha-overlay');
    expect(overlay.parentElement).toBe(document.body);
    expect(overlay).toHaveStyle({ position: 'fixed', pointerEvents: 'none' });
    expect(overlay).toContainElement(dock);
    expect(dock).toBeVisible();
    const user = userEvent.setup();
    const offer = await revealOffer();
    const page = screen.getByRole('main');
    expect(page).toContainElement(delay());
    expect(page).not.toContainElement(offer);
    expect(overlay).toContainElement(offer);
    expect(screen.queryByTestId('kesha-dock')).not.toBeInTheDocument();
    const attachedLauncher = within(offer).getByRole('button', { name: 'Открыть помощника' });
    expect(launcher()).toBe(attachedLauncher);
    expect(page).not.toContainElement(attachedLauncher);

    await user.click(attachedLauncher);
    expect(await screen.findByRole('textbox', { name: 'Ваш вопрос' })).toBeVisible();
    expect(screen.queryByRole('button', { name: applyLabel })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Закрыть помощника' }));
    expect(await screen.findByRole('button', { name: applyLabel })).toBeVisible();

    await setTargetVisible(false);
    expect(screen.getByTestId('kesha-dock')).toBeVisible();
    expect(offer).not.toBeVisible();
    await setTargetVisible(true);
    const repositionedOffer = await screen.findByLabelText(hintLabel);
    expect(within(repositionedOffer).getByRole('button', { name: 'Открыть помощника' })).toBe(launcher());
  }, 10_000);

  it('uses the existing chat for a crowded offer and returns focus to the delay field', async () => {
    vi.stubGlobal('innerWidth', 360);
    vi.stubGlobal('innerHeight', 520);
    const { mutations } = renderCabinet({ initialDelay: 5 });
    await ready();
    const field = delay();
    // Leave a strip for the invitation, but too little room to place the full card.
    const bounds = new Map<Element, DOMRect>([
      [screen.getByTestId('kesha-delay-target'), new DOMRect(12, 285, 336, 60)],
      [screen.getByLabelText('Дата платежа'), new DOMRect(12, 8, 336, 227)],
      [screen.getByRole('button', { name: 'Рассчитать и сохранить варианты' }), new DOMRect(12, 345, 336, 167)]
    ]);
    vi.mocked(Element.prototype.getBoundingClientRect).mockImplementation(function (this: Element) {
      return bounds.get(this) ?? rect;
    });
    await setTargetVisible(true);

    const invitation = await screen.findByRole('button', { name: 'Открыть подсказку Кеши в чате' });
    expect(screen.getByTestId('kesha-cloud')).not.toBeVisible();
    expect(screen.queryByTestId('kesha-chat-hint')).not.toBeInTheDocument();
    expect(field).toHaveValue(5);

    const user = userEvent.setup();
    await user.click(invitation);
    const inChat = await screen.findByTestId('kesha-chat-hint');
    expect(await screen.findByRole('textbox', { name: 'Ваш вопрос' })).toBeVisible();
    expect(inChat).toHaveTextContent(/с 5 на 2 дня/);
    expect(within(inChat).getByRole('button', { name: 'Не сейчас' })).toBeVisible();
    expect(screen.getByTestId('kesha-cloud')).not.toBeVisible();
    await user.click(within(inChat).getByRole('button', { name: applyLabel }));
    expect(field).toHaveValue(2);
    await user.click(within(inChat).getByRole('button', { name: 'Вернуться к полю' }));
    await waitFor(() => expect(field).toHaveFocus());

    await user.click(await screen.findByRole('button', { name: 'Открыть правку Кеши в чате' }));
    const correction = await screen.findByTestId('kesha-chat-hint');
    await user.click(within(correction).getByRole('button', { name: 'Отменить' }));
    await waitFor(() => expect(field).toHaveFocus());
    expect(field).toHaveValue(5);
    expect(send()).toBeEnabled();
    expect(mutations()).toEqual([]);
  }, 15_000);

  it('returns to the standard launcher when a config refresh turns off an open offer', async () => {
    const { disableFlag, mutations } = renderCabinet();
    await ready();
    const offer = await revealOffer();
    const apply = within(offer).getByRole('button', { name: applyLabel });
    disableFlag();
    await act(async () => document.dispatchEvent(new Event('visibilitychange')));
    expect(offer).not.toBeInTheDocument();
    expect(launcher()).toBeVisible();
    expect(screen.queryByTestId('kesha-dock')).not.toBeInTheDocument();
    fireEvent.click(apply);
    expect(delay()).toHaveValue(0);
    expect(mutations()).toEqual([]);
  });

  it('changes proactive suggestions from chat and keeps the preference isolated by account', async () => {
    async function openPreference() {
      await ready();
      await screen.findByTestId('kesha-dock');
      fireEvent.click(launcher());
      return screen.findByRole('checkbox', { name: 'Кеша сам предлагает подсказки' });
    }

    const first = renderCabinet();
    const checkbox = await openPreference();
    expect(checkbox).toBeChecked();
    fireEvent.click(checkbox);
    expect(checkbox).not.toBeChecked();
    fireEvent.click(screen.getByRole('button', { name: 'Закрыть помощника' }));
    expect(await screen.findByTestId('kesha-dock')).toBeVisible();
    await setTargetVisible(true, false);
    expect(screen.queryByRole('button', { name: applyLabel })).not.toBeInTheDocument();
    expect(delay()).toHaveValue(0);
    expect(first.mutations()).toEqual([]);
    first.unmount();

    const second = renderCabinet({ actorId: 'another-agent' });
    expect(await openPreference()).toBeChecked();
    expect(second.mutations()).toEqual([]);
    second.unmount();

    const returning = renderCabinet();
    const savedPreference = await openPreference();
    expect(savedPreference).not.toBeChecked();
    fireEvent.click(savedPreference);
    expect(savedPreference).toBeChecked();
    fireEvent.click(screen.getByRole('button', { name: 'Закрыть помощника' }));
    await setTargetVisible(true);
    expect(await screen.findByRole('button', { name: applyLabel }, { timeout: 3_000 })).toBeVisible();
    expect(delay()).toHaveValue(0);
    expect(returning.mutations()).toEqual([]);
  }, 15_000);

  it.each([false, 'unavailable'] as const)('keeps the standard launcher when configuration is %s', async (flag) => {
    const { requests } = renderCabinet({ flag });
    await ready();
    await waitFor(() => expect(requests.some(({ url }) => url === flagUrl)).toBe(true));
    expect(screen.queryByTestId('kesha-dock')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: applyLabel })).not.toBeInTheDocument();
    await userEvent.setup().click(launcher());
    expect(await screen.findByRole('textbox', { name: 'Ваш вопрос' })).toBeVisible();
  });

  it('does not mount the agent editor for a client and the standard chat opens on a permitted page', async () => {
    const { requests, router } = renderCabinet({ role: 'CLIENT' });
    expect(await screen.findByRole('heading', { name: 'Нет доступа' })).toBeVisible();
    expect(screen.queryByRole('spinbutton', { name: delayLabel })).not.toBeInTheDocument();
    await act(async () => router.navigate('/profile'));
    expect(screen.queryByTestId('kesha-dock')).not.toBeInTheDocument();
    expect(requests.some(({ url }) => url.startsWith('/agent/applications/'))).toBe(false);
    await userEvent.setup().click(launcher());
    expect(await screen.findByRole('textbox', { name: 'Ваш вопрос' })).toBeVisible();
  });

  it('keeps the hypothesis unavailable in an application the agent cannot edit', async () => {
    renderCabinet({ editable: false });
    await screen.findByRole('heading', { name: 'Заявка № 1042' });
    expect(screen.queryByRole('spinbutton', { name: delayLabel })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: applyLabel })).not.toBeInTheDocument();
  });

  it('does not propose a replacement for an unavailable financing scenario', async () => {
    const { mutations } = renderCabinet({ initialDelay: 'unavailable' });
    await ready();
    expect(screen.getByText('Нет соединения с сервером')).toBeVisible();
    await setTargetVisible(true);
    expect(screen.queryByRole('button', { name: applyLabel })).not.toBeInTheDocument();
    expect(mutations()).toEqual([]);
  });
});
