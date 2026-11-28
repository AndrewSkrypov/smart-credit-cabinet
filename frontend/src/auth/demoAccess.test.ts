import { describe, expect, it } from 'vitest';
import { resolveDemoAccess } from './demoAccess';

describe('demo routing scope', () => {
  it.each(['https://dev.credit-cabinet.ru', 'https://credit-cabinet.ru'])('resolves an exact path boundary on %s', (origin) => {
    for (const pathname of ['/demo', '/demo/', '/demo/sign-in', '/demo/client/applications/fixture']) {
      expect(resolveDemoAccess({ origin, pathname })).toEqual({ basename: '/demo', showPicker: false, localDemo: false });
    }
    for (const pathname of ['/', '/sign-in', '/demo-elsewhere', '/demonstration', '/Demo/sign-in']) {
      expect(resolveDemoAccess({ origin, pathname })).toEqual({ basename: '', showPicker: true, localDemo: false });
    }
  });

  it('enables an explicitly configured LAN origin and preserves the canonical demo proxy', () => {
    const origin = 'http://192.168.1.42:8080';
    expect(resolveDemoAccess({ origin, pathname: '/demo/sign-in' }, `${origin}/`)).toEqual({ basename: '/demo', showPicker: false, localDemo: true });
    expect(resolveDemoAccess({ origin: `${origin}1`, pathname: '/demo' }, origin).basename).toBe('');
    expect(resolveDemoAccess({ origin: 'https://credit-cabinet.ru', pathname: '/demo' }, 'https://credit-cabinet.ru').localDemo).toBe(false);
  });

  it.each([
    'http://dev.credit-cabinet.ru',
    'https://credit-cabinet.ru:444',
    'https://www.credit-cabinet.ru',
    'https://demo.dev.credit-cabinet.ru',
    'https://credit-cabinet.ru.example.test'
  ])('ignores demo paths on %s', (origin) => {
    expect(resolveDemoAccess({ origin, pathname: '/demo/sign-in' })).toEqual({ basename: '', showPicker: false, localDemo: false });
  });
});
