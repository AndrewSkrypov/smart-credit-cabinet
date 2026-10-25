import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { productEditorApi, productsApi, rulesApi } from '../../api/client';
import { ProductRevision, RulesResponse, UserSession } from '../../types';
import { ProductsPage } from './ProductsPage';
import { RulesPage } from './RulesPage';

let session: UserSession;
vi.mock('../../auth/AuthProvider', () => ({ useAuth: () => ({ session }) }));
vi.mock('../../api/client', () => ({
  productEditorApi: { list: vi.fn(), history: vi.fn(), save: vi.fn() },
  productsApi: { purposes: vi.fn() },
  rulesApi: { current: vi.fn(), version: vi.fn(), save: vi.fn() }
}));

const original: ProductRevision = {
  product: { code: 'WORKING_CAPITAL', version: 1, name: 'Оборотный кредит', description: 'Для оборота', purposes: ['WORKING_CAPITAL'], minAmount: 100000, maxAmount: 10000000, minTermMonths: 3, maxTermMonths: 60, baseRate: 18, autoApprovalLimit: 3000000, revolving: false, active: true },
  authorId: null, reason: 'Первая версия', createdAt: '2026-10-20T10:00:00Z'
};
const rules: RulesResponse = {
  version: 1,
  params: { maxLoad: 0.35, revenueMismatch: 0.3, ratingCutoffs: { A: 80, B: 65, C: 50, D: 35 }, ratingMargins: { A: 0, B: 1, C: 3, D: 5 }, lowRiskOkved: ['62'], highRiskOkved: ['41'], weakAmountShare: 0.5, weakRevenueShare: 0.15 },
  versions: [{ version: 1, createdAt: '2026-10-20T10:00:00Z', createdBy: null, comment: 'Первая версия' }]
};
function show(element: JSX.Element) {
  return render(<RouterProvider router={createMemoryRouter([{ path: '*', element }])} />);
}

describe('version editors', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    session = { token: 'synthetic', user: { id: 'risk', fullName: 'Редактор', email: 'risk@example.test', role: 'RISK_ADMIN', status: 'ACTIVE', companyId: null, canSign: false } };
    vi.mocked(productsApi.purposes).mockResolvedValue([{ code: 'WORKING_CAPITAL', title: 'Пополнение оборота' }]);
    vi.mocked(productEditorApi.list).mockResolvedValue([original.product]);
    vi.mocked(productEditorApi.history).mockResolvedValue([original]);
    vi.mocked(rulesApi.current).mockResolvedValue(rules);
  });

  it('confirms product saving and cancellation makes no request', async () => {
    const user = userEvent.setup();
    show(<ProductsPage />);
    const name = await screen.findByLabelText('Название продукта');
    await user.clear(name); await user.paste('Обновлённый кредит');
    await user.click(screen.getByLabelText(/Причина изменения продукта/));
    await user.paste('Обновление названия');
    await user.click(screen.getByRole('button', { name: 'Сохранить новую версию продукта' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Отмена' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(productEditorApi.save).not.toHaveBeenCalled();
    vi.mocked(productEditorApi.save).mockResolvedValue({ ...original, product: { ...original.product, version: 2, name: 'Обновлённый кредит' } });
    vi.mocked(productEditorApi.history).mockResolvedValue([{ ...original, product: { ...original.product, version: 2, name: 'Обновлённый кредит' } }, original]);
    await user.click(screen.getByRole('button', { name: 'Сохранить новую версию продукта' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'ОК' }));
    expect(await screen.findByText(/Сохранена версия 2/)).toBeInTheDocument();
    expect(productEditorApi.save).toHaveBeenCalledTimes(1);
    expect(productEditorApi.save).toHaveBeenCalledWith(expect.objectContaining({ name: 'Обновлённый кредит', version: 1 }), 'Обновление названия');
  });

  it('keeps the stale product draft after a conflict and asks before replacing it', async () => {
    const user = userEvent.setup();
    show(<ProductsPage />);
    const name = await screen.findByLabelText('Название продукта');
    await user.clear(name); await user.paste('Мой черновик');
    await user.click(screen.getByLabelText(/Причина изменения продукта/));
    await user.paste('Моя правка');
    vi.mocked(productEditorApi.save).mockRejectedValue(new Error('Конфликт версий'));
    vi.mocked(productEditorApi.history).mockResolvedValue([{ ...original, product: { ...original.product, version: 2, name: 'Правка коллеги' } }, original]);
    await user.click(screen.getByRole('button', { name: 'Сохранить новую версию продукта' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'ОК' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(screen.getByLabelText('Название продукта')).toHaveValue('Мой черновик');
    await user.click(screen.getByRole('button', { name: 'Загрузить текущие условия' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Вернуться к форме' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(screen.getByLabelText('Название продукта')).toHaveValue('Мой черновик');
    await user.click(screen.getByRole('button', { name: 'Загрузить текущие условия' }));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Продолжить без сохранения' }));
    await waitFor(() => expect(screen.getByLabelText('Название продукта')).toHaveValue('Правка коллеги'));
  });

  it('opens a rule snapshot read only for a manager', async () => {
    session.user.role = 'MANAGER';
    vi.mocked(rulesApi.version).mockResolvedValue({ version: 1, params: rules.params, metadata: rules.versions[0] });
    const user = userEvent.setup();
    show(<RulesPage />);
    expect(await screen.findByLabelText(/Предельная долговая нагрузка/)).toBeDisabled();
    expect(screen.queryByRole('button', { name: 'Сохранить новую версию' })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Посмотреть версию 1' }));
    const dialog = screen.getByRole('dialog');
    expect(await within(dialog).findByText(/Сохранённые условия/)).toBeInTheDocument();
    expect(within(dialog).queryByRole('textbox')).not.toBeInTheDocument();
    expect(rulesApi.version).toHaveBeenCalledWith(1);
  });
});
