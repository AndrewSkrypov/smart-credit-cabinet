import { createTheme, ThemeProvider } from '@mui/material';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { Role } from '../../types';
import { OnboardingHeaderMenu, OnboardingProvider, OnboardingWelcomeBanner } from './OnboardingTour';
import { findPageTour, onboardingStorageKey } from './tourSteps';

const testTheme = createTheme();

const mocks = vi.hoisted(() => ({
  role: 'AGENT' as Role,
  userId: '11111111-1111-1111-1111-111111111111'
}));

vi.mock('../../auth/AuthProvider', () => ({
  roleHome: {
    CLIENT: '/client',
    AGENT: '/agent',
    MANAGER: '/bank',
    UNDERWRITER: '/bank',
    RISK_ADMIN: '/bank'
  },
  useAuth: () => ({
    session: {
      token: 'tok',
      user: {
        id: mocks.userId,
        fullName: 'Тестовый пользователь',
        email: 'user@demo.test',
        role: mocks.role,
        companyId: mocks.role === 'CLIENT' ? '22222222-2222-2222-2222-222222222222' : null,
        canSign: mocks.role === 'CLIENT'
      }
    }
  })
}));

function renderTour(initialPath: string, role: Role, body: JSX.Element) {
  mocks.role = role;
  return render(
    <ThemeProvider theme={testTheme}>
      <MemoryRouter initialEntries={[initialPath]}>
        <OnboardingProvider>
          <OnboardingHeaderMenu />
          <OnboardingWelcomeBanner />
          <Routes>
            <Route path="*" element={body} />
          </Routes>
        </OnboardingProvider>
      </MemoryRouter>
    </ThemeProvider>
  );
}

describe('OnboardingTour', () => {
  beforeEach(() => {
    window.localStorage.clear();
    vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue(new DOMRect(12, 24, 200, 120));
  });
  afterEach(() => vi.restoreAllMocks());

  it('shows non-blocking first-visit banner for AGENT, walks through spotlight steps, and persists completion', () => {
    renderTour(
      '/agent',
      'AGENT',
      <div>
        <div data-tour="agent-kpi">KPI</div>
        <div data-tour="agent-clients">Clients</div>
        <div data-tour="agent-applications">Apps</div>
      </div>
    );

    expect(screen.getByTestId('onboarding-welcome-banner')).toBeInTheDocument();
    expect(screen.getByText('Впервые в кабинете агента?')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Начать обучение' }));
    expect(screen.getByRole('dialog', { name: /Шаг 1 из 3:/ })).toBeInTheDocument();
    const steps = screen.getByRole('group', { name: 'Шаги обучения' });
    const firstStep = screen.getByRole('button', { name: 'Перейти к шагу 1' });
    expect(steps).toContainElement(firstStep);
    expect(firstStep).toHaveAttribute('aria-current', 'step');
    expect(screen.getByRole('button', { name: 'Закрыть обучение' })).toHaveFocus();
    expect(screen.getByText('Сводка по клиентам и вознаграждению')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Далее' }));
    expect(screen.getByRole('dialog', { name: /Шаг 2 из 3:/ })).toBeInTheDocument();
    expect(screen.getByText('Закреплённые компании и персональные лимиты')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Назад' }));
    expect(screen.getByRole('dialog', { name: /Шаг 1 из 3:/ })).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Далее' }));
    fireEvent.click(screen.getByRole('button', { name: 'Далее' }));
    fireEvent.click(screen.getByRole('button', { name: 'Готово' }));

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(screen.queryByTestId('onboarding-welcome-banner')).not.toBeInTheDocument();
    expect(window.localStorage.getItem(onboardingStorageKey(mocks.userId, 'AGENT'))).toBe('completed');
  });

  it('skips absent optional targets for CLIENT and allows restart & reset from header menu', async () => {
    renderTour(
      '/client',
      'CLIENT',
      <div>
        <button data-tour="client-new-btn" type="button">Подобрать финансирование</button>
        <div data-tour="client-applications">Заявки</div>
      </div>
    );

    expect(screen.getByText('Впервые в кредитном кабинете?')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Скрыть' }));
    expect(screen.queryByTestId('onboarding-welcome-banner')).not.toBeInTheDocument();
    expect(window.localStorage.getItem(onboardingStorageKey(mocks.userId, 'CLIENT'))).toBe('dismissed');

    // Restart from "?" header menu without leaving the page
    fireEvent.click(screen.getByRole('button', { name: 'Обучение' }));
    fireEvent.click(screen.getByRole('menuitem', { name: /Пройти интерактивный гид/ }));

    // Only the 2 present targets are shown (optional pre-approved block skipped gracefully)
    expect(screen.getByRole('dialog', { name: /Шаг 1 из 2:/ })).toBeInTheDocument();
    fireEvent.keyDown(screen.getByRole('button', { name: 'Закрыть обучение' }), { key: 'Escape' });
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole('button', { name: 'Обучение' })).toHaveFocus());

    // Reset shows the welcome banner again
    fireEvent.click(screen.getByRole('button', { name: 'Обучение' }));
    fireEvent.click(screen.getByRole('menuitem', { name: /Показать приглашение снова/ }));
    expect(screen.getByTestId('onboarding-welcome-banner')).toBeInTheDocument();
  });

  it('renders role-specific copy for bank staff (UNDERWRITER and RISK_ADMIN)', () => {
    const { unmount } = renderTour('/bank', 'UNDERWRITER', <div data-tour="bank-queue-tabs">Очередь</div>);
    expect(screen.getByText('Гид по рабочему месту андеррайтера')).toBeInTheDocument();
    unmount();

    renderTour('/bank', 'RISK_ADMIN', <div data-tour="bank-queue-tabs">Очередь</div>);
    expect(screen.getByText('Гид риск-администратора')).toBeInTheDocument();
  });
  it('offers a page tour on the client application card and stores its progress separately', () => {
    renderTour(
      '/client/applications/abc',
      'CLIENT',
      <div>
        <div data-tour="app-status">Этапы</div>
        <div data-tour="app-confirm">Подтверждение</div>
        <div data-tour="app-documents">Документы</div>
      </div>
    );

    expect(screen.getByText('Впервые в карточке заявки?')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Начать обучение' }));
    expect(screen.getByRole('dialog', { name: /Шаг 1 из 3:/ })).toBeInTheDocument();
    expect(screen.getByText('Где сейчас заявка и что нужно от вас')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Далее' }));
    expect(screen.getByText('Проверьте заявку, подготовленную агентом')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Далее' }));
    fireEvent.click(screen.getByRole('button', { name: 'Готово' }));

    expect(window.localStorage.getItem(onboardingStorageKey(mocks.userId, 'CLIENT', 'client-application'))).toBe('completed');
    expect(window.localStorage.getItem(onboardingStorageKey(mocks.userId, 'CLIENT'))).toBeNull();
    expect(screen.queryByTestId('onboarding-welcome-banner')).not.toBeInTheDocument();
  });

  it('starts the page tour or the cabinet tour from the header menu and resets every tour of the role', async () => {
    renderTour(
      '/bank/applications/42',
      'UNDERWRITER',
      <div>
        <div data-tour="bank-queue-tabs">Очередь</div>
        <div data-tour="bank-app-decision">Решение</div>
        <div data-tour="bank-app-advice">Советы</div>
      </div>
    );
    fireEvent.click(screen.getByRole('button', { name: 'Скрыть' }));
    expect(window.localStorage.getItem(onboardingStorageKey(mocks.userId, 'UNDERWRITER', 'bank-application'))).toBe('dismissed');

    fireEvent.click(screen.getByRole('button', { name: 'Обучение' }));
    fireEvent.click(screen.getByRole('menuitem', { name: /Гид по этой странице/ }));
    expect(screen.getByText('Решение по заявке и его обоснование')).toBeInTheDocument();
    fireEvent.keyDown(screen.getByRole('button', { name: 'Закрыть обучение' }), { key: 'Escape' });
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());

    fireEvent.click(screen.getByRole('button', { name: 'Обучение' }));
    fireEvent.click(screen.getByRole('menuitem', { name: /Пройти интерактивный гид/ }));
    expect(await screen.findByText('Очередь заявок и просрочки SLA')).toBeInTheDocument();
    fireEvent.keyDown(screen.getByRole('button', { name: 'Закрыть обучение' }), { key: 'Escape' });
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());

    fireEvent.click(screen.getByRole('button', { name: 'Обучение' }));
    fireEvent.click(screen.getByRole('menuitem', { name: /Показать приглашение снова/ }));
    expect(window.localStorage.getItem(onboardingStorageKey(mocks.userId, 'UNDERWRITER', 'bank-application'))).toBeNull();
    expect(window.localStorage.getItem(onboardingStorageKey(mocks.userId, 'UNDERWRITER'))).toBeNull();
  });

  it('matches page tours by role and path', () => {
    expect(findPageTour('CLIENT', '/client/applications/1')?.id).toBe('client-application');
    expect(findPageTour('AGENT', '/agent/applications/1')?.id).toBe('agent-application');
    expect(findPageTour('AGENT', '/agent/clients/7')?.id).toBe('agent-client');
    expect(findPageTour('MANAGER', '/bank/applications/1')?.id).toBe('bank-application');
    expect(findPageTour('RISK_ADMIN', '/bank/products')?.id).toBe('bank-products');
    expect(findPageTour('UNDERWRITER', '/bank/products')?.id).toBe('bank-products');
    expect(findPageTour('CLIENT', '/bank/applications/1')).toBeNull();
    expect(findPageTour('CLIENT', '/client')).toBeNull();
  });

  it('describes the signing code where the client actually sees it', () => {
    renderTour('/client', 'CLIENT', <div data-tour="notifications-bell">Колокольчик</div>);
    fireEvent.click(screen.getByRole('button', { name: 'Начать обучение' }));
    expect(screen.queryByText(/коды подписания ПЭП приходят/)).not.toBeInTheDocument();
  });
});
