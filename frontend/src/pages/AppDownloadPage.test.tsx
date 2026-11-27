import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AppDownloadPage, detectPlatform, otaLink, type AppDownloads } from './AppDownloadPage';

const downloads = (overrides: Partial<AppDownloads> = {}): AppDownloads => ({
  android: { rustoreUrl: null, apkUrl: '/downloads/kabinet.apk', version: '1.0.0', sizeMb: 2.9 },
  ios: { otaManifestUrl: null, version: '1.0.0' },
  ...overrides,
});

function serve(data: AppDownloads | null) {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(data ? { ok: true, json: () => Promise.resolve(data) } : { ok: false, status: 404 }));
}

afterEach(() => vi.unstubAllGlobals());

describe('AppDownloadPage', () => {
  it('detects phones, including iPadOS that reports a desktop Safari', () => {
    expect(detectPlatform('Mozilla/5.0 (Linux; Android 14; Pixel 8)')).toBe('android');
    expect(detectPlatform('Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X)')).toBe('ios');
    expect(detectPlatform('Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)', 5)).toBe('ios');
    expect(detectPlatform('Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)', 0)).toBe('desktop');
  });

  it('offers the signed APK on Android while RuStore is not published', async () => {
    serve(downloads());
    render(<MemoryRouter><AppDownloadPage platform="android" /></MemoryRouter>);
    const apk = await screen.findByRole('link', { name: /Скачать APK · 2,9 МБ/ });
    expect(apk).toHaveAttribute('href', '/downloads/kabinet.apk');
    expect(screen.queryByRole('link', { name: 'Скачать в RuStore' })).not.toBeInTheDocument();
    expect(screen.getByText(/Публикация в RuStore на модерации/)).toBeInTheDocument();
  });

  it('puts RuStore first once the store link is published', async () => {
    serve(downloads({ android: { rustoreUrl: 'https://www.rustore.ru/catalog/app/ru.creditcabinet.pump', apkUrl: '/downloads/kabinet.apk', version: '1.0.0' } }));
    render(<MemoryRouter><AppDownloadPage platform="android" /></MemoryRouter>);
    expect(await screen.findByRole('link', { name: 'Скачать в RuStore' })).toHaveAttribute('href', 'https://www.rustore.ru/catalog/app/ru.creditcabinet.pump');
  });

  it('installs over the air on iPhone when a manifest is published and explains Home Screen install otherwise', async () => {
    serve(downloads({ ios: { otaManifestUrl: 'https://credit-cabinet.ru/downloads/ios/manifest.plist', version: '1.0.0' } }));
    const { unmount } = render(<MemoryRouter><AppDownloadPage platform="ios" /></MemoryRouter>);
    expect(await screen.findByRole('link', { name: 'Установить на iPhone' }))
      .toHaveAttribute('href', otaLink('https://credit-cabinet.ru/downloads/ios/manifest.plist'));
    unmount();
    serve(downloads());
    render(<MemoryRouter><AppDownloadPage platform="ios" /></MemoryRouter>);
    expect(await screen.findByText(/На экран „Домой“/)).toBeInTheDocument();
  });

  it('shows a QR code on a computer and a warning when links cannot be loaded', async () => {
    serve(null);
    render(<MemoryRouter><AppDownloadPage platform="desktop" /></MemoryRouter>);
    expect(await screen.findByText(/Не удалось загрузить ссылки/)).toBeInTheDocument();
    expect(screen.getByRole('img', { name: 'QR-код страницы приложения' })).toBeInTheDocument();
  });
});
