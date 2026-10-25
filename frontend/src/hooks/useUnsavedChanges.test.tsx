import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, Link, RouterProvider } from 'react-router-dom';
import { expect, it } from 'vitest';
import { useState } from 'react';
import { useUnsavedChanges } from './useUnsavedChanges';

function Editor() {
  const [text, setText] = useState('');
  const guard = useUnsavedChanges(!!text);
  return <><input aria-label="Правка" value={text} onChange={(event) => setText(event.target.value)} /><Link to="/other">Другая вкладка</Link>{guard.dialog}</>;
}

it('keeps a draft when navigation is cancelled and leaves only after explicit discard', async () => {
  const router = createMemoryRouter([{ path: '/edit', element: <Editor /> }, { path: '/other', element: <h1>Другая вкладка</h1> }], { initialEntries: ['/edit'] });
  render(<RouterProvider router={router} />);
  await userEvent.type(screen.getByLabelText('Правка'), 'Черновик');
  await userEvent.click(screen.getByRole('link', { name: 'Другая вкладка' }));
  expect(screen.getByRole('dialog')).toBeInTheDocument();
  await userEvent.click(screen.getByRole('button', { name: 'Вернуться к форме' }));
  await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
  expect(screen.getByLabelText('Правка')).toHaveValue('Черновик');
  expect(router.state.location.pathname).toBe('/edit');
  await userEvent.click(screen.getByRole('link', { name: 'Другая вкладка' }));
  await userEvent.click(screen.getByRole('button', { name: 'Продолжить без сохранения' }));
  expect(await screen.findByRole('heading', { name: 'Другая вкладка' })).toBeInTheDocument();
  expect(router.state.location.pathname).toBe('/other');
});
