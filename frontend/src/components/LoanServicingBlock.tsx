import {
  Alert,
  Box,
  Button,
  Chip,
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
import { FormEvent, useCallback, useEffect, useState } from 'react';
import { agentApi, loanServicingApi } from '../api/client';
import { formatDate, formatDateTime, months, percent, rub, rubExact } from '../shared/format';
import { LoanAccount } from '../types';
import { ErrorState } from './ErrorState';
import { LoadingBlock } from './LoadingBlock';
import { AgentPaymentReserveBlock, PaymentReserveBlock } from './PaymentReserveBlock';
import { TableScrollRegion } from './TableScrollRegion';

function Metric({ label, value }: { label: string; value: string }) {
  return (
    <Box sx={{ minWidth: 0 }}>
      <Typography variant="body2" color="text.secondary">{label}</Typography>
      <Typography fontWeight={600} sx={{ overflowWrap: 'anywhere' }}>{value}</Typography>
    </Box>
  );
}

export function LoanServicingBlock({
  applicationId,
  readOnly = false,
  agentScope = false
}: {
  applicationId: string;
  readOnly?: boolean;
  agentScope?: boolean;
}) {
  const [loan, setLoan] = useState<LoanAccount | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busyAction, setBusyAction] = useState<'tranche' | 'pay' | 'repay' | null>(null);
  const [trancheAmount, setTrancheAmount] = useState('');
  const [tranchePurpose, setTranchePurpose] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setLoadError(null);
    try {
      const data = agentScope
        ? await agentApi.getLoan(applicationId)
        : await loanServicingApi.getByApplication(applicationId);
      setLoan(data);
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
  const canMutate = !readOnly && isActive;
  const remainingLimit = Math.max(0, loan.approvedLimit - loan.disbursedAmount);
  const canDrawTranche = canMutate && loan.revolving && remainingLimit > 0;

  const handleDrawTranche = async (event: FormEvent) => {
    event.preventDefault();
    const numericAmount = Number(trancheAmount);
    if (!Number.isFinite(numericAmount) || numericAmount <= 0) {
      setActionError('Укажите сумму транша больше нуля.');
      return;
    }
    if (numericAmount > remainingLimit) {
      setActionError(`Сумма транша не может превышать доступный лимит (${rub(remainingLimit)}).`);
      return;
    }
    setBusyAction('tranche');
    setActionError(null);
    setNotice(null);
    try {
      const updated = await loanServicingApi.drawTranche(loan.id, numericAmount, tranchePurpose.trim() || undefined);
      setLoan(updated);
      setTrancheAmount('');
      setTranchePurpose('');
      setNotice(`Транш на ${rub(numericAmount)} успешно выдан. График платежей пересчитан.`);
    } catch (nextError) {
      setActionError(nextError instanceof Error ? nextError.message : 'Не удалось выдать транш');
    } finally {
      setBusyAction(null);
    }
  };

  const handlePayNext = async () => {
    setBusyAction('pay');
    setActionError(null);
    setNotice(null);
    try {
      const updated = await loanServicingApi.payNext(loan.id);
      setLoan(updated);
      setNotice(updated.status === 'REPAID' ? 'Последний платёж проведён — кредит полностью погашен.' : 'Очередной платёж проведён.');
    } catch (nextError) {
      setActionError(nextError instanceof Error ? nextError.message : 'Не удалось провести платёж');
    } finally {
      setBusyAction(null);
    }
  };

  const handleRepayEarly = async () => {
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

        <Grid container spacing={2}>
          <Grid item xs={6} sm={4}>
            <Metric label="Одобренный лимит" value={rub(loan.approvedLimit)} />
          </Grid>
          <Grid item xs={6} sm={4}>
            <Metric label="Выбрано" value={rub(loan.disbursedAmount)} />
          </Grid>
          {loan.revolving && (
            <Grid item xs={6} sm={4}>
              <Metric label="Доступный лимит" value={rub(loan.availableLimit)} />
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
                  disabled={busyAction !== null}
                  sx={{ flex: 1 }}
                />
                <TextField
                  label="Назначение транша (необязательно)"
                  size="small"
                  value={tranchePurpose}
                  onChange={(event) => setTranchePurpose(event.target.value)}
                  inputProps={{ maxLength: 255 }}
                  disabled={busyAction !== null}
                  sx={{ flex: 1.5 }}
                />
                <Button
                  type="submit"
                  variant="contained"
                  disabled={busyAction !== null}
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
                  disabled={busyAction !== null}
                  onClick={() => void handlePayNext()}
                >
                  {busyAction === 'pay' ? 'Проводим…' : 'Внести очередной платёж'}
                </Button>
                <Button
                  variant="outlined"
                  color="warning"
                  size="small"
                  disabled={busyAction !== null}
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
    </Paper>
  );
}
