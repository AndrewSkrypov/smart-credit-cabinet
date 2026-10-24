import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { expect, it, vi } from 'vitest';
import { employeesApi } from '../../api/client';
import { Employees } from '../../types';
import { EmployeesPage } from './EmployeesPage';

vi.mock('../../api/client', () => ({ employeesApi: { list: vi.fn() } }));
vi.mock('../../auth/AuthProvider', () => ({ useAuth: () => ({ session: { user: { id: 'self' } }, signOut: vi.fn() }) }));

it('offers revocation for another active employee, never for the current user', async () => {
  vi.mocked(employeesApi.list).mockResolvedValue({
    canManage: true, mailAvailable: false, invitations: [],
    members: [
      { id: 'self', fullName: 'Текущий подписант', email: 'self@demo.test', role: 'CLIENT', companyId: 'company', canSign: true, status: 'ACTIVE' },
      { id: 'other', fullName: 'Другой подписант', email: 'other@demo.test', role: 'CLIENT', companyId: 'company', canSign: true, status: 'ACTIVE' }
    ]
  } as Employees);
  render(<MemoryRouter><EmployeesPage /></MemoryRouter>);
  expect(await screen.findByText('Текущий подписант (вы)')).toBeInTheDocument();
  const actions = screen.getAllByRole('button', { name: 'Отозвать доступ' });
  expect(actions).toHaveLength(1);
  await userEvent.click(actions[0]);
  expect(screen.getByRole('dialog')).toHaveTextContent('Другой подписант');
  expect(screen.getByRole('dialog')).not.toHaveTextContent('Текущий подписант');
});
