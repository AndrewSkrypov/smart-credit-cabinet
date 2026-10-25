import { FinancingPurpose, Role } from '../types';

const PURPOSES: FinancingPurpose[] = ['CASH_GAP', 'CURRENT_EXPENSES', 'WORKING_CAPITAL', 'EQUIPMENT', 'VEHICLE', 'RENOVATION', 'BUSINESS_DEVELOPMENT'];
export interface FinancingSelection { purpose?: FinancingPurpose; product?: string; amount?: number; termMonths?: number }

/** Public input only pre-fills the wizard; product eligibility and prices are always requested again. */
export function readFinancingSelection(search: string): FinancingSelection {
  const params = new URLSearchParams(search);
  const purpose = params.get('purpose') as FinancingPurpose;
  const product = params.get('product') ?? '';
  const amount = Number(params.get('amount'));
  const termMonths = Number(params.get('termMonths'));
  return {
    purpose: PURPOSES.includes(purpose) ? purpose : undefined,
    product: /^[A-Z0-9_]{1,50}$/.test(product) ? product : undefined,
    amount: Number.isSafeInteger(amount) && amount > 0 && amount <= 1_000_000_000_000 ? amount : undefined,
    termMonths: Number.isInteger(termMonths) && termMonths >= 1 && termMonths <= 120 ? termMonths : undefined
  };
}

export function financingPath(startPath: string, selection: FinancingSelection): string {
  if (startPath !== '/client/new' && startPath !== '/sign-up') return startPath;
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(selection)) if (value !== undefined) params.set(key, String(value));
  const query = params.toString();
  const next = `/client/new${query ? `?${query}` : ''}`;
  return startPath === '/sign-up' ? `/sign-up?${new URLSearchParams({ next })}` : next;
}

export function roleDestination(role: Role, search: string, home: string): string {
  const requested = new URLSearchParams(search).get('next');
  if (!requested || !requested.startsWith('/')) return home;
  try {
    const url = new URL(requested, 'https://cabinet.invalid');
    const area = role === 'CLIENT' ? '/client' : '/bank';
    const ownArea = url.pathname === area || url.pathname.startsWith(`${area}/`);
    return url.origin === 'https://cabinet.invalid' && ownArea ? `${url.pathname}${url.search}${url.hash}` : home;
  } catch { return home; }
}

export function authPath(path: '/sign-in' | '/sign-up', search: string): string {
  const next = new URLSearchParams(search).get('next');
  return next ? `${path}?${new URLSearchParams({ next })}` : path;
}
