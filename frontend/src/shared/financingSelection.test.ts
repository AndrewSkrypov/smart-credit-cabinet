import { describe, expect, it } from 'vitest';
import { authPath, financingPath, readFinancingSelection, roleDestination } from './financingSelection';

describe('financing selection through authentication', () => {
  it('preserves a calculation when switching between registration and sign in', () => {
    const selected = { purpose: 'EQUIPMENT' as const, product: 'INVESTMENT', amount: 2500000, termMonths: 36 };
    const registration = financingPath('/sign-up', selected);
    const signIn = authPath('/sign-in', registration.slice(registration.indexOf('?')));
    const destination = roleDestination('CLIENT', signIn.slice(signIn.indexOf('?')), '/client');
    expect(readFinancingSelection(destination.slice(destination.indexOf('?')))).toEqual(selected);
    expect(financingPath('/bank', selected)).toBe('/bank');
  });
  it('refuses external, escaped and cross-role redirect destinations', () => {
    for (const next of ['https://evil.test/client/new', '//evil.test/client/new', '/client/../bank', '/bank/rules', '/client/%2e%2e/bank']) {
      expect(roleDestination('CLIENT', `?${new URLSearchParams({ next })}`, '/client')).toBe('/client');
    }
    expect(roleDestination('MANAGER', '?next=%2Fclient%2Fnew', '/bank')).toBe('/bank');
  });
  it('ignores malformed prefill values and never interprets them as an application', () => {
    expect(readFinancingSelection('?purpose=HACK&product=../oops&amount=-4&termMonths=999')).toEqual({ purpose: undefined, product: undefined, amount: undefined, termMonths: undefined });
  });
});
