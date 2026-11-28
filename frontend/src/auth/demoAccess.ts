export const DEMO_BASE = '/demo';
export const JURY_BASE = '/demo/jury';

export function resolveDemoAccess({ origin, pathname }: Pick<Location, 'origin' | 'pathname'>) {
  if (pathname === JURY_BASE || pathname.startsWith(`${JURY_BASE}/`)) {
    return { basename: JURY_BASE, showPicker: false };
  }
  const supported = origin === 'https://dev.credit-cabinet.ru' || origin === 'https://credit-cabinet.ru';
  const inDemo = supported && (pathname === DEMO_BASE || pathname.startsWith(`${DEMO_BASE}/`));
  return { basename: inDemo ? DEMO_BASE : '', showPicker: supported && !inDemo };
}

// Crossing the boundary uses a full navigation to initialise the router, API and session together.
export const demoAccess = resolveDemoAccess(window.location);

export function cabinetPathname(pathname: string): string {
  return pathname.slice(demoAccess.basename.length) || '/';
}
