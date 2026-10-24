import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { authApi } from '../api/client';
import { AuthProvider } from '../auth/AuthProvider';
import { writeSession } from '../auth/session';
import { UserSession } from '../types';
import { ProfilePage } from './ProfilePage';

vi.mock('../api/client', () => ({
  authApi: { updateProfile: vi.fn(), changePassword: vi.fn(), logoutAll: vi.fn() },
  notificationsApi: { settings: vi.fn().mockResolvedValue({ emailAvailable: false, emailEnabled: false, version: 0 }), saveSettings: vi.fn() }
}));

const session = {
  token: 'old',
  user: { id: 'u1', fullName: 'Ирина Котова', email: 'client@demo.test', role: 'CLIENT', status: 'ACTIVE', companyId: 'c1', canSign: true }
} as UserSession;

function renderProfile() {
  return render(
    <MemoryRouter>
      <AuthProvider>
        <ProfilePage />
      </AuthProvider>
    </MemoryRouter>
  );
}

describe('ProfilePage personal data', () => {
  beforeEach(() => {
    localStorage.clear();
    writeSession(session);
    vi.mocked(authApi.updateProfile).mockReset();
  });

  it('saves a new name without a password and keeps the new session', async () => {
    vi.mocked(authApi.updateProfile).mockResolvedValue({ token: 'new', user: { ...session.user, fullName: 'Ирина Петрова' } });
    renderProfile();
    const save = screen.getByRole('button', { name: 'Сохранить данные' });
    expect(save).toBeDisabled();
    const name = screen.getByLabelText(/Имя и фамилия/);
    await userEvent.clear(name);
    await userEvent.type(name, 'Ирина Петрова');
    expect(screen.queryByLabelText(/Текущий пароль для смены email/)).not.toBeInTheDocument();
    await userEvent.click(save);
    expect(authApi.updateProfile).toHaveBeenCalledWith('Ирина Петрова', 'client@demo.test', undefined);
    expect(await screen.findByText('Данные сохранены')).toBeInTheDocument();
    expect(JSON.parse(localStorage.getItem('cabinet.session')!).token).toBe('new');
  });

  it('asks for the current password when the email changes', async () => {
    vi.mocked(authApi.updateProfile).mockResolvedValue({ token: 'new', user: { ...session.user, email: 'irina@demo.test' } });
    renderProfile();
    const email = screen.getByLabelText(/^Email/);
    await userEvent.clear(email);
    await userEvent.type(email, 'irina@demo.test');
    await userEvent.type(screen.getByLabelText(/Текущий пароль для смены email/), 'Demo2026');
    await userEvent.click(screen.getByRole('button', { name: 'Сохранить данные' }));
    expect(authApi.updateProfile).toHaveBeenCalledWith('Ирина Котова', 'irina@demo.test', 'Demo2026');
  });
});
