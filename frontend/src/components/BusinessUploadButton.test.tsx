import { AxiosError } from 'axios';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { agentApi, api } from '../api/client';
import { UploadButton } from '../pages/client/application/UploadButton';
import { BusinessUploadButton } from './BusinessUploadButton';
import { businessUploadScope } from '../shared/businessUpload';

const originalAdapter = api.defaults.adapter;
afterEach(() => { cleanup(); api.defaults.adapter = originalAdapter; });
const file = () => new File(['%PDF-1.4 synthetic'], 'document.pdf', { type: 'application/pdf' });

function choose(container: HTMLElement, selected = file()) {
  fireEvent.change(container.querySelector('input[type="file"]')!, { target: { files: [selected] } });
}

function Upload({ actor, id = 'app-1' }: { actor: 'client' | 'agent'; id?: string }) {
  const common = { onUploaded: () => undefined, onError: () => undefined };
  return actor === 'client'
    ? <UploadButton applicationId={id} documentType="OTHER" privateToBank {...common} />
    : <BusinessUploadButton scope={businessUploadScope('agent', id, 'OTHER')}
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
});
