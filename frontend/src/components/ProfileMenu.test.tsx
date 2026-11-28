import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { initials, ProfileMenu } from './ProfileMenu';
import { User } from '../types';

const user: User = { id: 'u1', fullName: 'Котова Ирина Сергеевна', email: 'client@demo.test', role: 'CLIENT', status: 'ACTIVE', companyId: 'c1', canSign: true };

describe('ProfileMenu', () => {
  it('keeps profile and sign-out behind one avatar', async () => {
    const onSignOut = vi.fn();
    render(<MemoryRouter><ProfileMenu user={user} onSignOut={onSignOut} /></MemoryRouter>);
    expect(screen.queryByRole('menuitem', { name: 'Выйти' })).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Меню профиля' }));
    expect(screen.getByText('client@demo.test')).toBeInTheDocument();
    expect(screen.getByRole('menuitem', { name: 'Профиль' })).toHaveAttribute('href', '/profile');
    await userEvent.click(screen.getByRole('menuitem', { name: 'Выйти' }));
    expect(onSignOut).toHaveBeenCalledOnce();
  });

  it('builds initials from the first two words', () => {
    expect(initials('Котова Ирина Сергеевна')).toBe('КИ');
    expect(initials('Агент')).toBe('АГ');
  });
});
