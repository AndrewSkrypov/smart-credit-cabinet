import { describe, expect, it } from 'vitest';
import { resolveDemoAccess } from './demoAccess';

describe('demo routing scope', () => {
  it.each(['https://dev.credit-cabinet.ru', 'https://credit-cabinet.ru'])('resolves an exact path boundary on %s', (origin) => {
    for (const pathname of ['/demo', '/demo/', '/demo/sign-in', '/demo/client/applications/fixture']) {
      expect(resolveDemoAccess({ origin, pathname })).toEqual({ basename: '/demo', showPicker: false });
    }
    for (const pathname of ['/', '/sign-in', '/demo-elsewhere', '/demonstration', '/Demo/sign-in']) {
      expect(resolveDemoAccess({ origin, pathname })).toEqual({ basename: '', showPicker: true });
    }
  });

  it.each([
    'http://dev.credit-cabinet.ru',
    'https://credit-cabinet.ru:444',
    'https://www.credit-cabinet.ru',
    'https://demo.dev.credit-cabinet.ru',
    'https://credit-cabinet.ru.example.test'
  ])('ignores demo paths on %s', (origin) => {
    expect(resolveDemoAccess({ origin, pathname: '/demo/sign-in' })).toEqual({ basename: '', showPicker: false });
  });
});
