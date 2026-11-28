import { describe, expect, it } from 'vitest';
import { resolveDemoAccess } from './demoAccess';

describe('demo routing scope', () => {
  it.each(['https://dev.credit-cabinet.ru', 'http://localhost:3000'])('separates jury paths from public demo on %s', (origin) => {
    for (const pathname of ['/demo/jury', '/demo/jury/', '/demo/jury/bank']) {
      expect(resolveDemoAccess({ origin, pathname })).toEqual({ basename: '/demo/jury', showPicker: false });
    }
  });

  it.each(['https://dev.credit-cabinet.ru', 'https://credit-cabinet.ru'])('resolves an exact path boundary on %s', (origin) => {
    for (const pathname of ['/demo', '/demo/', '/demo/sign-in', '/demo/client/applications/fixture', '/demo/jury-elsewhere']) {
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
