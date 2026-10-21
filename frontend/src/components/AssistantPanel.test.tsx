import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { AssistantPanel } from './AssistantPanel';

const ask = vi.fn().mockResolvedValue({ text: 'Банк просит <b>декларацию</b>.', mode: 'RULES', sources: ['Заявка № 1042'] });

vi.mock('../api/client', () => ({
  assistantApi: {
    status: vi.fn().mockResolvedValue({ mode: 'RULES', model: null }),
    ask: (...args: unknown[]) => ask(...args)
  }
}));

const APPLICATION = '6f1d4c1e-0b8e-4c55-9a51-000000000001';

describe('AssistantPanel', () => {
  it('asks about the application on screen and shows the answer as plain text', async () => {
    render(
      <MemoryRouter initialEntries={[`/client/applications/${APPLICATION}`]}>
        <AssistantPanel />
      </MemoryRouter>
    );
    await userEvent.click(screen.getByRole('button', { name: 'Открыть помощника' }));
    expect(await screen.findByText(/Справочный режим/)).toBeInTheDocument();
    await userEvent.click(screen.getByText('Какие документы нужно загрузить?'));
    expect(ask).toHaveBeenCalledWith('Какие документы нужно загрузить?', APPLICATION, []);
    expect(await screen.findByText('Банк просит <b>декларацию</b>.')).toBeInTheDocument();
    expect(screen.getByText('Источники: Заявка № 1042')).toBeInTheDocument();
  });
});
