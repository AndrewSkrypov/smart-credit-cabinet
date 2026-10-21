import { describe, expect, it } from 'vitest';
import { generateTestInn, isValidInn } from './inn';

describe('ИНН', () => {
  it('checks the digits like the tax service', () => {
    expect(isValidInn('7707083893')).toBe(true);
    expect(isValidInn('7707083894')).toBe(false);
    expect(isValidInn('500100732259')).toBe(true);
    expect(isValidInn('12345')).toBe(false);
  });

  it('generates valid synthetic numbers of both kinds', () => {
    for (let i = 0; i < 50; i += 1) {
      const organisation = generateTestInn(true);
      const entrepreneur = generateTestInn(false);
      expect(organisation).toMatch(/^00\d{8}$/);
      expect(entrepreneur).toMatch(/^00\d{10}$/);
      expect(isValidInn(organisation)).toBe(true);
      expect(isValidInn(entrepreneur)).toBe(true);
    }
  });
});
