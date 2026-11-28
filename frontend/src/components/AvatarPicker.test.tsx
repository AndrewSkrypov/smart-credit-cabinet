import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AvatarPicker } from './AvatarPicker';
import { User } from '../types';

const changeAvatar = vi.fn();
vi.mock('../api/client', () => ({ authApi: { changeAvatar: (avatar: string | null) => changeAvatar(avatar) } }));

const user: User = { id: 'u1', fullName: 'Котова Ирина', email: 'client@demo.test', role: 'CLIENT', status: 'ACTIVE', companyId: 'c1', canSign: true, avatar: null };

describe('AvatarPicker', () => {
  beforeEach(() => changeAvatar.mockReset());

  it('saves a preset at once and offers to go back to initials', async () => {
    changeAvatar.mockResolvedValue({ ...user, avatar: 'preset:rocket' });
    const onChanged = vi.fn();
    const { rerender } = render(<AvatarPicker user={user} onChanged={onChanged} />);
    expect(screen.queryByRole('button', { name: 'Вернуть инициалы' })).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole('radio', { name: 'Ракета' }));
    await waitFor(() => expect(onChanged).toHaveBeenCalledWith(expect.objectContaining({ avatar: 'preset:rocket' })));
    expect(changeAvatar).toHaveBeenCalledWith('preset:rocket');
    rerender(<AvatarPicker user={{ ...user, avatar: 'preset:rocket' }} onChanged={onChanged} />);
    expect(screen.getByRole('radio', { name: 'Ракета' })).toHaveAttribute('aria-checked', 'true');
    changeAvatar.mockResolvedValue(user);
    await userEvent.click(screen.getByRole('button', { name: 'Вернуть инициалы' }));
    await waitFor(() => expect(onChanged).toHaveBeenLastCalledWith(user));
    expect(changeAvatar).toHaveBeenLastCalledWith(null);
  });

  it('shows the server message when saving fails', async () => {
    changeAvatar.mockImplementationOnce(() => Promise.reject(new Error('Фото слишком большое — уменьшите его до 64 КБ')));
    render(<AvatarPicker user={user} onChanged={vi.fn()} />);
    await userEvent.click(screen.getByRole('radio', { name: 'Банк' }));
    expect(await screen.findByText('Фото слишком большое — уменьшите его до 64 КБ')).toBeInTheDocument();
  });
});
