import { ReactElement, ReactNode, Children, isValidElement } from 'react';
import { render } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { expect, it } from 'vitest';
import { AppRoutes } from '../routes/AppRoutes';
import { AppShell } from './AppShell';
import { DocumentCanvas, DocumentSurface, Surface } from './DocumentSurface';
import { AuthFrame, SignInPage, SignUpPage } from '../pages/AuthPages';
import { AcceptInvitationPage, PasswordRecoveryPage, PasswordResetPage } from '../pages/AccountAccessPages';
import { AccessDeniedPage, NotFoundPage } from '../pages/SystemPages';
import { LandingPage } from '../pages/landing/LandingPage';

it('covers every declared route by a layout family or explicit system fallback', () => {
  const known = new Map<unknown, Surface>([[AppShell, 'workspace'], [AuthFrame, 'auth'], [SignInPage, 'auth'], [SignUpPage, 'auth'],
    [AcceptInvitationPage, 'auth'], [PasswordRecoveryPage, 'auth'], [PasswordResetPage, 'auth'], [AccessDeniedPage, 'light'], [NotFoundPage, 'light'], [LandingPage, 'landing']]);
  const covered: Record<string, Surface> = {};
  function visit(node: ReactNode, inherited?: Surface) {
    Children.forEach(node, (child) => {
      if (!isValidElement(child)) return;
      const props = child.props as { children?: ReactNode; path?: string; element?: ReactElement };
      const own = props.element?.type === DocumentSurface ? (props.element.props as { kind: Surface }).kind : known.get(props.element?.type);
      const family = own ?? inherited;
      if (props.path) { expect(family, `Unmapped route ${props.path}`).toBeDefined(); covered[props.path] = family!; }
      visit(props.children, family);
    });
  }
  const routes = AppRoutes();
  visit(routes.props.children);
  expect(covered['/']).toBe('landing');
  expect(covered['/sign-up']).toBe('auth');
  expect(covered['/client/applications/:id']).toBe('workspace');
  expect(covered['/bank/products']).toBe('workspace');
  expect(covered['*']).toBe('light');
});

it('releases only its own canvas claim through transitions, errors and cleanup', () => {
  function Layout({ kind, error = false }: { kind: Surface; error?: boolean }) {
    return <DocumentCanvas><MemoryRouter><DocumentSurface kind={kind}>Содержимое</DocumentSurface>{error && <DocumentSurface kind="light" priority={1}>Ошибка</DocumentSurface>}</MemoryRouter></DocumentCanvas>;
  }
  const { rerender, unmount } = render(<Layout kind="landing" />);
  expect(document.documentElement.dataset.surface).toBe('landing');
  for (const kind of ['auth', 'workspace', 'light', 'auth'] as Surface[]) {
    rerender(<Layout kind={kind} />);
    expect(document.documentElement.dataset.surface).toBe(kind);
  }
  rerender(<Layout kind="workspace" error />);
  expect(document.documentElement.dataset.surface).toBe('light');
  rerender(<Layout kind="auth" />);
  expect(document.documentElement.dataset.surface).toBe('auth');
  unmount();
  expect(document.documentElement.dataset.surface).toBe('light');
});
