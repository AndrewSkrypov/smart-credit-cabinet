import { AxiosError } from 'axios';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { agentApi, api } from '../api/client';
import { UploadButton } from '../pages/client/application/UploadButton';
import { BusinessUploadButton } from './BusinessUploadButton';
import { writeSession } from '../auth/session';
import { UserSession } from '../types';
import { businessUploadScope } from '../shared/businessUpload';

const originalAdapter = api.defaults.adapter;
afterEach(() => { cleanup(); api.defaults.adapter = originalAdapter; localStorage.clear(); });

const setActor = (token: string) => writeSession({ token, user: { id: token, role: 'CLIENT' } } as UserSession);

const file = () => new File(['%PDF-1.4 synthetic'], 'document.pdf', { type: 'application/pdf' });

function choose(container: HTMLElement, selected = file()) {
  fireEvent.change(container.querySelector('input[type="file"]')!, { target: { files: [selected] } });
}

function Upload({ actor, id = 'app-1' }: { actor: 'client' | 'agent'; id?: string }) {
  const common = { onUploaded: () => undefined, onError: () => undefined };
  return actor === 'client'
    ? <UploadButton applicationId={id} documentType="OTHER" privateToBank {...common} />
    : <BusinessUploadButton scope={() => businessUploadScope('agent', id, 'OTHER')}
      upload={(selected, key) => agentApi.uploadDocument(id, 'OTHER', selected, key)} {...common} />;
}

describe('business upload operation', () => {
  it.each(['client', 'agent'] as const)('retries a lost %s response with the same HTTP key; a new selection gets a new key', async (actor) => {
    const keys: unknown[] = [];
    api.defaults.adapter = async (config) => {
      expect(config.url).toBe(`${actor === 'agent' ? '/agent' : ''}/applications/app-1/documents`);
      keys.push(config.headers.get('Idempotency-Key'));
      if (keys.length === 1) throw new AxiosError('lost response', 'ERR_NETWORK', config);
      return { data: { id: 'document' }, status: 200, statusText: 'OK', headers: {}, config };
    };
    const { container } = render(<Upload actor={actor} />);
    const selected = file();
    choose(container, selected);
    fireEvent.click(await screen.findByRole('button', { name: /Повторить загрузку/ }));
    await waitFor(() => expect(screen.queryByRole('button', { name: /Повторить загрузку/ })).toBeNull());
    choose(container, selected);
    await waitFor(() => expect(keys).toHaveLength(3));
    expect(keys[0]).toEqual(expect.any(String));
    expect(keys[1]).toBe(keys[0]);
    expect(keys[2]).not.toBe(keys[0]);
  });

  it('retains 503 retries and isolates pending uploads between applications and instances', async () => {
    const keys: unknown[] = [];
    api.defaults.adapter = async (config) => {
      keys.push(config.headers.get('Idempotency-Key'));
      throw new AxiosError('unavailable', 'ERR_BAD_RESPONSE', config, undefined, {
        config, status: 503, statusText: 'Unavailable', headers: {}, data: { message: 'Повторите позже' }
      });
    };
    const view = render(<Upload actor="client" />);
    choose(view.container);
    fireEvent.click(await screen.findByRole('button', { name: /Повторить загрузку/ }));
    await waitFor(() => expect(keys).toHaveLength(2));
    expect(keys[0]).toBe(keys[1]);
    view.rerender(<Upload actor="client" id="app-2" />);
    expect(screen.queryByRole('button', { name: /Повторить загрузку/ })).toBeNull();
    choose(view.container);
    await screen.findByRole('button', { name: /Повторить загрузку/ });
    const second = render(<Upload actor="client" id="app-2" />);
    choose(second.container);
    await waitFor(() => expect(keys).toHaveLength(4));
    expect(new Set([keys[0], keys[2], keys[3]]).size).toBe(3);
  });
  it.each(['client', 'agent'] as const)('refuses a stale %s retry without rerender and allows an explicit new file', async (actor) => {
    setActor('A');
    const requests: { key: unknown; token: unknown }[] = [];
    api.defaults.adapter = async (config) => {
      requests.push({ key: config.headers.get('Idempotency-Key'), token: config.headers.Authorization });
      if (requests.length === 1) throw new AxiosError('lost response', 'ERR_NETWORK', config);
      return { data: {}, status: 200, statusText: 'OK', headers: {}, config };
    };
    const view = render(<Upload actor={actor} />);
    choose(view.container);
    const retry = await screen.findByRole('button', { name: /Повторить загрузку/ });
    setActor('B'); // Storage changed in another tab; React has not rendered again.
    fireEvent.click(retry);
    expect(requests).toHaveLength(1);
    expect(screen.queryByRole('button', { name: /Повторить загрузку/ })).toBeNull();
    choose(view.container);
    await waitFor(() => expect(requests).toHaveLength(2));
    expect(requests[1].token).toBe('Bearer B');
    expect(requests[1].key).not.toBe(requests[0].key);
  });

  it.each(['success', 'error'])('discards a delayed %s after the actor changes', async (outcome) => {
    setActor('A');
    let finish!: () => void;
    const upload = vi.fn().mockImplementationOnce(() => new Promise<void>((resolve, reject) => {
      finish = () => outcome === 'success' ? resolve() : reject(new Error('old error'));
    })).mockResolvedValue(undefined);
    const onUploaded = vi.fn();
    const onError = vi.fn();
    const view = render(<BusinessUploadButton scope={() => businessUploadScope('client', 'app')}
      upload={upload} onUploaded={onUploaded} onError={onError} />);
    choose(view.container);
    setActor('B');
    await act(async () => finish());
    expect(onUploaded).not.toHaveBeenCalled();
    expect(onError).toHaveBeenCalledWith('Выберите файл заново, чтобы продолжить.');
    expect(screen.queryByRole('button', { name: /Повторить загрузку/ })).toBeNull();
    choose(view.container);
    await waitFor(() => expect(onUploaded).toHaveBeenCalledTimes(1));
    expect(upload.mock.calls[1][1]).not.toBe(upload.mock.calls[0][1]);
  });

});
