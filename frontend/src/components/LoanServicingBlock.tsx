import {
  Alert,
  Box,
  Button,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Divider,
  Grid,
  Paper,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  TextField,
  Typography
} from '@mui/material';
import { FormEvent, useCallback, useEffect, useRef, useState } from 'react';
import { agentApi, ApiError, loanServicingApi } from '../api/client';
import { useAuth } from '../auth/AuthProvider';
import { readSession } from '../auth/session';
import { formatDate, formatDateTime, months, percent, rub, rubExact } from '../shared/format';
import { LoanAccount, User } from '../types';
import { ErrorState } from './ErrorState';
import { LoadingBlock } from './LoadingBlock';
import { AgentPaymentReserveBlock, PaymentReserveBlock } from './PaymentReserveBlock';
import { TableScrollRegion } from './TableScrollRegion';

type LoanOperation =
  | { kind: 'tranche'; amount: number; purpose?: string }
  | { kind: 'pay'; amount: number; paymentId: string; dueDate: string; paymentNumber: number };

type PendingOperation = { loanId: string; key: string; operation: LoanOperation };

function operationStorageKey(user: User | undefined, applicationId: string): string | null {
  if (user?.role !== 'CLIENT' || !user.id || !user.companyId) return null;
  return `cabinet.loan-operation:v2:${JSON.stringify([user.id, user.companyId, applicationId])}`;
}

function readPending(storageKey: string): PendingOperation | null {
  try {
    const saved = sessionStorage.getItem(storageKey);
    return saved ? JSON.parse(saved) as PendingOperation : null;
  } catch {
    return null;
  }
}

const moscowDate = new Intl.DateTimeFormat('sv-SE', { timeZone: 'Europe/Moscow', dateStyle: 'short' });

function Metric({ label, value }: { label: string; value: string }) {
  return (
    <Box sx={{ minWidth: 0 }}>
      <Typography variant="body2" color="text.secondary">{label}</Typography>
      <Typography fontWeight={600} sx={{ overflowWrap: 'anywhere' }}>{value}</Typography>
    </Box>
  );
}

interface LoanServicingProps {
  applicationId: string;
  readOnly?: boolean;
  agentScope?: boolean;
}

export function LoanServicingBlock({ applicationId, readOnly = false, agentScope = false }: LoanServicingProps) {
  const { session } = useAuth();
  if (!session) return null;
  const { user } = session;
  const storageKey = readOnly || agentScope ? null : operationStorageKey(user, applicationId);
  return (
    <ScopedLoanServicingBlock
      key={JSON.stringify([user.id, user.companyId, user.role, applicationId, readOnly, agentScope])}
      applicationId={applicationId}
      readOnly={readOnly}
      agentScope={agentScope}
      storageKey={storageKey}
    />
  );
}

function ScopedLoanServicingBlock({
  applicationId, readOnly, agentScope, storageKey
}: Required<LoanServicingProps> & { storageKey: string | null }) {
  const [loan, setLoan] = useState<LoanAccount | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busyAction, setBusyAction] = useState<'tranche' | 'pay' | 'repay' | null>(null);
  const [trancheAmount, setTrancheAmount] = useState('');
  const [tranchePurpose, setTranchePurpose] = useState('');
  const [confirmation, setConfirmation] = useState<LoanOperation | null>(null);
  const [pending, setPending] = useState(() => storageKey ? readPending(storageKey) : null);
  const inFlight = useRef(false);

  const ownsCurrentSession = () => storageKey !== null && operationStorageKey(readSession()?.user, applicationId) === storageKey;

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const data = agentScope
        ? await agentApi.getLoan(applicationId)
        : await loanServicingApi.getByApplication(applicationId);
      setLoan(data);
      setLoadError(null);
    } catch (nextError) {
      setLoadError(nextError instanceof Error ? nextError.message : 'Не удалось загрузить данные по обслуживанию кредита');
    } finally {
      setLoading(false);
    }
  }, [agentScope, applicationId]);

  useEffect(() => {
    void load();
  }, [load]);

  if (loading && !loan) return <LoadingBlock />;
  if (loadError && !loan) return <ErrorState message={loadError} onRetry={() => void load()} />;
  if (!loan) return null;

  const isActive = loan.status === 'ACTIVE';
  const canMutate = storageKey !== null && isActive;
  const currentPending = pending?.loanId === loan.id ? pending : null;
  const locked = busyAction !== null || currentPending !== null || loading || loadError !== null;
  const nextPayment = loan.payments.find(payment => payment.status === 'SCHEDULED');
  const today = moscowDate.format(new Date());
  const drawPeriodOpen = today < loan.maturityDate && loan.payments.some(payment => payment.status === 'SCHEDULED' && payment.dueDate > today);
  const remainingLimit = drawPeriodOpen ? loan.availableLimit : 0;
  const canDrawTranche = canMutate && loan.revolving && remainingLimit > 0;

  const handleDrawTranche = (event: FormEvent) => {
    event.preventDefault();
    if (locked) return;
    const numericAmount = Number(trancheAmount);
    if (!Number.isSafeInteger(numericAmount) || numericAmount <= 0) {
      setActionError('Укажите сумму транша в целых рублях.');
      return;
    }
    if (numericAmount > remainingLimit) {
      setActionError(`Сумма транша не может превышать доступный лимит (${rub(remainingLimit)}).`);
      return;
    }
    setActionError(null);
    setConfirmation({ kind: 'tranche', amount: numericAmount, purpose: tranchePurpose.trim() || undefined });
  };

  const applyOperation = async (attempt: PendingOperation, retry = false) => {
    if (inFlight.current || !storageKey || !ownsCurrentSession()) return;
    inFlight.current = true;
    const { operation, key, loanId } = attempt;
    const clearOperation = () => {
      setPending(current => current?.key === key ? null : current);
      if (readPending(storageKey)?.key !== key) return;
      try {
        sessionStorage.removeItem(storageKey);
      } catch { /* Storage may be unavailable. */ }
    };

    setConfirmation(null);
    setPending(attempt);
    try {
      sessionStorage.setItem(storageKey, JSON.stringify(attempt));
    } catch { /* The in-memory receipt remains available while this page is open. */ }
    setBusyAction(operation.kind);
    setActionError(null);
    setNotice(null);
    try {
      const updated = operation.kind === 'pay'
        ? await loanServicingApi.payNext(loanId, operation.paymentId, key)
        : await loanServicingApi.drawTranche(loanId, operation.amount, operation.purpose, key);
      if (!retry) setLoan(updated);
      clearOperation();
      if (operation.kind === 'tranche') {
        setTrancheAmount('');
        setTranchePurpose('');
        setNotice(`Транш на ${rub(operation.amount)} выдан. График платежей пересчитан.`);
      } else {
        setNotice(updated.status === 'REPAID' ? 'Последний платёж проведён — кредит полностью погашен.' : `Платёж № ${operation.paymentNumber} на ${rubExact(operation.amount)} проведён.`);
      }
      if (retry && ownsCurrentSession()) await load();
    } catch (nextError) {
      setActionError(nextError instanceof Error ? nextError.message : 'Не удалось получить результат операции');
      if (nextError instanceof ApiError && nextError.status < 500 && nextError.status !== 429) {
        clearOperation();
        if (nextError.status === 409 && ownsCurrentSession()) await load();
      }
    } finally {
      inFlight.current = false;
      setBusyAction(null);
    }
  };

  const handleRepayEarly = async () => {
    if (!ownsCurrentSession()) return;
    setBusyAction('repay');
    setActionError(null);
    setNotice(null);
    try {
      const updated = await loanServicingApi.repayEarly(loan.id);
      setLoan(updated);
      setNotice('Кредит полностью погашен досрочно.');
    } catch (nextError) {
      setActionError(nextError instanceof Error ? nextError.message : 'Не удалось выполнить досрочное погашение');
    } finally {
      setBusyAction(null);
    }
  };

  return (
    <Paper variant="outlined" sx={{ p: { xs: 2, md: 3 } }}>
      <Stack spacing={2.5}>
        <Stack direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between" alignItems={{ xs: 'flex-start', sm: 'center' }} spacing={1} flexWrap="wrap" useFlexGap>
          <Box sx={{ minWidth: 0 }}>
            <Typography variant="h6" component="h2">Обслуживание кредита и транши</Typography>
            <Typography variant="body2" color="text.secondary" sx={{ overflowWrap: 'anywhere' }}>
              Договор {loan.contractNumber} · до {formatDate(loan.maturityDate)} ({months(loan.termMonths)})
            </Typography>
          </Box>
          <Chip
            label={isActive ? 'Активен' : 'Погашен'}
            color={isActive ? 'success' : 'default'}
            size="small"
          />
        </Stack>

        {readOnly && (
          <Alert severity="info">
            Режим просмотра: выборка траншей и погашение кредита доступны только клиенту.
          </Alert>
        )}

        {notice && <Alert severity="success" onClose={() => setNotice(null)}>{notice}</Alert>}
        {actionError && <Alert severity="error" onClose={() => setActionError(null)}>{actionError}</Alert>}
        {loadError && (
          <Alert
            severity="warning"
            action={
              <Button variant="outlined" size="small" disabled={loading || busyAction !== null} onClick={() => void load()}>
                Обновить график
              </Button>
            }
          >
            Не удалось обновить график платежей.
          </Alert>
        )}
        {currentPending && (
          <Alert severity="info" action={<Button disabled={busyAction !== null} onClick={() => void applyOperation(currentPending, true)}>Повторить</Button>}>
            {busyAction ? 'Получаем результат операции.' : 'Результат операции пока неизвестен. Повторите запрос с теми же данными.'}
          </Alert>
        )}

        <Grid container spacing={2}>
          <Grid item xs={6} sm={4}>
            <Metric label="Одобренный лимит" value={rub(loan.approvedLimit)} />
          </Grid>
          <Grid item xs={6} sm={4}>
            <Metric label="Выбрано" value={rub(loan.disbursedAmount)} />
          </Grid>
          {loan.revolving && (
            <Grid item xs={6} sm={4}>
              <Metric label="Доступный лимит" value={rub(remainingLimit)} />
            </Grid>
          )}
          <Grid item xs={6} sm={4}>
            <Metric label="Остаток долга" value={rubExact(loan.outstandingPrincipal)} />
          </Grid>
          <Grid item xs={6} sm={4}>
            <Metric label="Ставка" value={percent(loan.rate)} />
          </Grid>
          <Grid item xs={6} sm={4}>
            <Metric label="Тип линии" value={loan.revolving ? 'Возобновляемая' : 'Разовая выдача'} />
          </Grid>
        </Grid>

        {loan.revolving && (agentScope
          ? <AgentPaymentReserveBlock applicationId={applicationId} />
          : !readOnly && <PaymentReserveBlock key={loan.id} loanId={loan.id} active={isActive} onDrawn={load} />)}
        <Divider />

        <Box>
          <Typography variant="subtitle1" component="h3" fontWeight={600} gutterBottom>
            Выданные транши ({loan.tranches.length})
          </Typography>
          <TableScrollRegion label="Выданные транши">
            <Table size="small" aria-label="Выданные транши">
              <TableHead>
                <TableRow>
                  <TableCell>№</TableCell>
                  <TableCell align="right">Сумма</TableCell>
                  <TableCell>Назначение</TableCell>
                  <TableCell>Дата выдачи</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {loan.tranches.map((tranche) => (
                  <TableRow key={tranche.id}>
                    <TableCell>{tranche.trancheNumber}</TableCell>
                    <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>{rub(tranche.amount)}</TableCell>
                    <TableCell>{tranche.purpose ?? '—'}</TableCell>
                    <TableCell sx={{ whiteSpace: 'nowrap' }}>{formatDateTime(tranche.disbursedAt)}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableScrollRegion>

          {canDrawTranche && (
            <Box component="form" onSubmit={(event) => void handleDrawTranche(event)} sx={{ mt: 2, p: 2, borderRadius: 2, bgcolor: 'background.default' }}>
              <Typography variant="subtitle2" fontWeight={600} gutterBottom>
                Запросить дополнительный транш (доступно {rub(remainingLimit)})
              </Typography>
              <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5} alignItems={{ xs: 'stretch', sm: 'flex-start' }}>
                <TextField
                  label="Сумма транша, ₽"
                  type="number"
                  size="small"
                  required
                  value={trancheAmount}
                  onChange={(event) => setTrancheAmount(event.target.value)}
                  inputProps={{ min: 1, max: remainingLimit, step: 1, inputMode: 'numeric' }}
                  disabled={locked}
                  sx={{ flex: 1 }}
                />
                <TextField
                  label="Назначение транша (необязательно)"
                  size="small"
                  value={tranchePurpose}
                  onChange={(event) => setTranchePurpose(event.target.value)}
                  inputProps={{ maxLength: 255 }}
                  disabled={locked}
                  sx={{ flex: 1.5 }}
                />
                <Button
                  type="submit"
                  variant="contained"
                  disabled={locked}
                  sx={{ whiteSpace: 'nowrap', minHeight: 40 }}
                >
                  {busyAction === 'tranche' ? 'Выдаём…' : 'Запросить транш'}
                </Button>
              </Stack>
            </Box>
          )}
        </Box>

        <Divider />

        <Box>
          <Stack direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between" alignItems={{ xs: 'stretch', sm: 'center' }} spacing={1.5} flexWrap="wrap" useFlexGap sx={{ mb: 1.5 }}>
            <Typography variant="subtitle1" component="h3" fontWeight={600}>
              График платежей
            </Typography>
            {canMutate && (
              <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1} flexWrap="wrap" useFlexGap>
                <Button
                  variant="contained"
                  size="small"
                  disabled={locked || !nextPayment}
                  onClick={() => nextPayment && setConfirmation({
                    kind: 'pay',
                    amount: nextPayment.paymentAmount,
                    paymentId: nextPayment.id,
                    dueDate: nextPayment.dueDate,
                    paymentNumber: nextPayment.paymentNumber,
                  })}
                >
                  {busyAction === 'pay' ? 'Проводим…' : 'Внести очередной платёж'}
                </Button>
                <Button
                  variant="outlined"
                  color="warning"
                  size="small"
                  disabled={locked}
                  onClick={() => void handleRepayEarly()}
                >
                  {busyAction === 'repay' ? 'Погашаем…' : 'Досрочно погасить'}
                </Button>
              </Stack>
            )}
          </Stack>

          <TableScrollRegion label="График обслуживания кредита">
            <Table size="small" aria-label="График обслуживания кредита">
              <TableHead>
                <TableRow>
                  <TableCell>№</TableCell>
                  <TableCell>Дата</TableCell>
                  <TableCell align="right">Платёж</TableCell>
                  <TableCell align="right">Основной долг</TableCell>
                  <TableCell align="right">Проценты</TableCell>
                  <TableCell align="right">Остаток</TableCell>
                  <TableCell>Статус</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {loan.payments.map((payment) => (
                  <TableRow key={payment.id}>
                    <TableCell>{payment.paymentNumber}</TableCell>
                    <TableCell sx={{ whiteSpace: 'nowrap' }}>{formatDate(payment.dueDate)}</TableCell>
                    <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>{rubExact(payment.paymentAmount)}</TableCell>
                    <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>{rubExact(payment.principalAmount)}</TableCell>
                    <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>{rubExact(payment.interestAmount)}</TableCell>
                    <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>{rubExact(payment.remainingBalance)}</TableCell>
                    <TableCell>
                      <Chip
                        size="small"
                        label={payment.status === 'PAID' ? 'Оплачен' : 'Запланирован'}
                        color={payment.status === 'PAID' ? 'success' : 'default'}
                        variant={payment.status === 'PAID' ? 'filled' : 'outlined'}
                      />
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableScrollRegion>
        </Box>
      </Stack>
      <Dialog open={confirmation !== null} onClose={() => setConfirmation(null)} fullWidth maxWidth="xs"
        aria-labelledby="loan-operation-title">
        <DialogTitle id="loan-operation-title">
          {confirmation?.kind === 'pay' ? 'Подтвердить платёж?' : 'Подтвердить выдачу транша?'}
        </DialogTitle>
        {confirmation && (
          <DialogContent>
            <Typography fontWeight={600}>{rubExact(confirmation.amount)}</Typography>
            {confirmation.kind === 'pay'
              ? <Typography color="text.secondary">Платёж № {confirmation.paymentNumber} от {formatDate(confirmation.dueDate)}</Typography>
              : confirmation.purpose && <Typography color="text.secondary">{confirmation.purpose}</Typography>}
          </DialogContent>
        )}
        <DialogActions>
          <Button onClick={() => setConfirmation(null)}>Назад</Button>
          <Button variant="contained" onClick={() => confirmation && void applyOperation({
            loanId: loan.id, key: crypto.randomUUID(), operation: confirmation,
          })}>Подтвердить</Button>
        </DialogActions>
      </Dialog>
    </Paper>
  );
}
