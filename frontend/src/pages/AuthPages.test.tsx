import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { AuthProvider } from '../auth/AuthProvider';
import { SignUpPage } from './AuthPages';

vi.mock('../api/client', () => ({
  authApi: { options: vi.fn().mockResolvedValue({ demoAccounts: false }), signUp: vi.fn(), signIn: vi.fn() }
}));

function renderSignUp() {
  return render(
    <MemoryRouter>
      <AuthProvider>
        <SignUpPage />
      </AuthProvider>
    </MemoryRouter>
  );
}

describe('SignUpPage', () => {
  it('cannot be sent without the consent to personal data processing', async () => {
    renderSignUp();
    const submit = screen.getByRole('button', { name: 'Подключить' });
    expect(submit).toBeDisabled();
    await userEvent.click(screen.getByRole('checkbox'));
    expect(submit).toBeEnabled();
  });

  it('tells how many digits of the INN are missing', async () => {
    renderSignUp();
    await userEvent.type(screen.getByLabelText(/ИНН компании или ИП/), '12345');
    expect(screen.getByText('Сейчас 5 цифр — нужно 10 или 12')).toBeInTheDocument();
  });
});
