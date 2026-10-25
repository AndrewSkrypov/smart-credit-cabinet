import { render, screen, within, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { expect, it, vi } from 'vitest';
import { RevisionMetadata, VersionHistory, VersionPreviewDialog } from './VersionHistory';

const revisions = (count: number): RevisionMetadata[] => Array.from({ length: count }, (_, i) => ({
  version: i + 1, createdAt: '2026-10-25T10:00:00Z', authorId: 'author-uuid', authorName: i ? 'Редактор' : null,
  authorRole: 'RISK_ADMIN', reason: `Причина ${i + 1}`
}));

it.each([0, 1, 11, 20, 21, 100])('bounds the DOM for %i versions and exposes all pages', async (count) => {
  const user = userEvent.setup();
  const open = vi.fn();
  render(<VersionHistory title="История" items={revisions(count)} currentVersion={count} onOpen={open} />);
  expect(screen.queryAllByRole('button', { name: /^Посмотреть версию/ })).toHaveLength(Math.min(count, 5));
  expect(!!screen.queryByRole('navigation')).toBe(count > 5);
  if (count > 5) {
    for (let page = 1; page < Math.ceil(count / 5); page++) await user.click(screen.getByRole('button', { name: 'Далее' }));
    await user.click(screen.getByRole('button', { name: 'Посмотреть версию 1' }));
    expect(open).toHaveBeenCalledWith(1);
  }
});

it('preserves the current page anchor when a new version arrives or the parent rerenders', async () => {
  const user = userEvent.setup();
  const { rerender } = render(<VersionHistory title="История" items={revisions(100)} currentVersion={100} onOpen={vi.fn()} />);
  for (let i = 0; i < 16; i++) await user.click(screen.getByRole('button', { name: 'Далее' }));
  expect(screen.getByRole('button', { name: 'Посмотреть версию 20' })).toBeInTheDocument();
  rerender(<VersionHistory title="История" items={revisions(101)} currentVersion={101} onOpen={vi.fn()} />);
  expect(screen.getByRole('button', { name: 'Посмотреть версию 20' })).toBeInTheDocument();
  rerender(<VersionHistory title="История" items={revisions(101)} currentVersion={101} onOpen={vi.fn()} />);
  await waitFor(() => expect(screen.getByRole('button', { name: 'Посмотреть версию 20' })).toBeInTheDocument());
  expect(screen.getAllByRole('button', { name: /^Посмотреть версию/ })).toHaveLength(5);
});

it('returns focus after read-only preview and keeps the active page', async () => {
  const user = userEvent.setup();
  function History() {
    const [viewed, setViewed] = useState<number | null>(null);
    return <><VersionHistory title="История" items={revisions(21)} currentVersion={21} onOpen={setViewed} />
      {viewed !== null && <VersionPreviewDialog title="Снимок" metadata={revisions(21)[viewed - 1]} currentVersion={21} onClose={() => setViewed(null)}>Условия</VersionPreviewDialog>}</>;
  }
  render(<History />);
  for (let i = 0; i < 4; i++) await user.click(screen.getByRole('button', { name: 'Далее' }));
  const opener = screen.getByRole('button', { name: 'Посмотреть версию 1' });
  await user.click(opener);
  const dialog = screen.getByRole('dialog');
  expect(within(dialog).getByText('Архивная')).toBeInTheDocument();
  expect(within(dialog).getByText('Сейчас действует версия 21')).toBeInTheDocument();
  expect(within(dialog).getByText('Автор: author-uuid · Риск-администратор')).toBeInTheDocument();
  await user.keyboard('{Escape}');
  await waitFor(() => expect(opener).toHaveFocus());
  expect(screen.getByText('21–21 из 21')).toBeInTheDocument();
});
