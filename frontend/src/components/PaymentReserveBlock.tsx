import { Alert, Box, Button, Grid, Paper, Stack, TextField, Typography } from '@mui/material';
import { FormEvent, useEffect, useRef, useState } from 'react';
import { paymentReserveApi, PaymentReserveInputs, PaymentReserveQuote } from '../api/financing';
import { useApi } from '../hooks/useApi';
import { formatDate, rub } from '../shared/format';
import { MoneyField } from './MoneyField';

const actions = {
  WAIT_FOR_RECEIPT: 'Ожидается поступление',
  NO_FINANCING_NEEDED: 'Транш не требуется',
  CLIENT_CONFIRM_DRAW: 'Клиенту нужно подтвердить транш',
  REVIEW_REQUIRED: 'Нужно пересмотреть план',
  TRANCHE_CONFIRMED: 'Транш подтверждён'
};

export function AgentPaymentReserveBlock({ applicationId }: { applicationId: string }) {
  const { data, error, reload } = useApi(() => paymentReserveApi.agent(applicationId), [applicationId]);
  useEffect(() => {
    const timer = window.setInterval(() => void reload(), 15000);
    return () => window.clearInterval(timer);
  }, [applicationId, reload]);
  if (!data || error) return null;
  return (
    <Paper variant="outlined" sx={{ p: 2 }}>
      <Typography variant="subtitle1" component="h3">Резерв к дате платежа</Typography>
      <Typography variant="body2" color="text.secondary">{actions[data.action]} · платёж {formatDate(data.paymentDate)}</Typography>
    </Paper>
  );
}

type ReserveForm = Pick<PaymentReserveInputs, 'controlDate' | 'paymentDate'> &
  Record<'openingBalance' | 'expectedReceiptAmount' | 'payableAmount' | 'targetBuffer', number | null>;

const emptyForm: ReserveForm = {
  openingBalance: null, expectedReceiptAmount: null, payableAmount: null, targetBuffer: null, controlDate: '', paymentDate: ''
};

const moneyFields = [
  ['openingBalance', 'Остаток до поступления'],
  ['expectedReceiptAmount', 'Ожидаемое поступление'],
  ['payableAmount', 'Платёж поставщику'],
  ['targetBuffer', 'Запас после платежа']
] as const;

function describeQuote(quote: PaymentReserveQuote) {
  if (quote.drawnAmount > 0) return `Транш ${rub(quote.drawnAmount)} подтверждён`;
  if (quote.receiptStatus === 'PENDING') return 'Ожидается поступление';
  if (quote.requiredAmount === 0) return 'Транш не требуется';
  if (quote.shortfall > 0) return `Лимита недостаточно. До целевого остатка не хватает ${rub(quote.shortfall)}.`;
  if (quote.canDraw) return 'Доступна выборка по действующей линии';
  return 'Проверьте дату платежа и действующий лимит';
}

export function PaymentReserveBlock({ loanId, active, onDrawn }: { loanId: string; active: boolean; onDrawn: () => Promise<void> }) {
  const [enabled, setEnabled] = useState(false);
  const [quote, setQuote] = useState<PaymentReserveQuote | null>(null);
  const [form, setForm] = useState<ReserveForm>(emptyForm);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const receiptEvent = useRef<{ id: string; status: 'RECEIVED' | 'DELAYED' } | null>(null);

  useEffect(() => {
    let current = true;
    setEnabled(false);
    void paymentReserveApi.get(loanId).then((data) => {
      if (!current) return;
      setEnabled(data.enabled);
      setQuote(data.quote);
      setForm(data.quote?.inputs ?? emptyForm);
    }).catch(() => {
      if (current) setEnabled(false);
    });
    return () => { current = false; };
  }, [loanId]);

  if (!enabled) return null;

  const complete = moneyFields.every(([name]) => form[name] != null) &&
    (form.payableAmount ?? 0) > 0 && (form.expectedReceiptAmount ?? 0) > 0 &&
    !!form.controlDate && !!form.paymentDate && form.controlDate <= form.paymentDate;
  const editing = !quote || Object.keys(form).some((field) => form[field as keyof ReserveForm] !== quote.inputs[field as keyof ReserveForm]);

  async function run(operation: () => Promise<PaymentReserveQuote>, refreshLoan = false) {
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      setQuote(await operation());
      receiptEvent.current = null;
      if (refreshLoan) await onDrawn();
    } catch (next) {
      setError(next instanceof Error ? next.message : 'Не удалось обновить резерв');
    } finally {
      setBusy(false);
    }
  }

  function save(event: FormEvent) {
    event.preventDefault();
    if (!complete || !active) return;
    void run(() => paymentReserveApi.save(loanId, form as PaymentReserveInputs));
  }

  function recordReceipt(status: 'RECEIVED' | 'DELAYED') {
    if (receiptEvent.current?.status !== status) receiptEvent.current = { id: crypto.randomUUID(), status };
    const eventId = receiptEvent.current.id;
    void run(() => paymentReserveApi.receipt(loanId, eventId, status));
  }

  async function refresh() {
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      const next = await paymentReserveApi.get(loanId);
      setEnabled(next.enabled);
      setQuote(next.quote);
      setForm(next.quote?.inputs ?? emptyForm);
      receiptEvent.current = null;
    } catch (next) {
      setError(next instanceof Error ? next.message : 'Не удалось обновить расчёт');
    } finally {
      setBusy(false);
    }
  }

  return (
    <Box>
      <Typography variant="subtitle1" component="h3" fontWeight={600} gutterBottom>Резерв к дате платежа</Typography>
      <Stack spacing={1.5}>
        {error && <Alert severity="error" action={<Button disabled={busy} onClick={() => void refresh()}>Обновить расчёт</Button>}>{error}</Alert>}
        {active && !quote?.drawnAmount && (
          <Stack component="form" onSubmit={save} spacing={1.5}>
            <Grid container spacing={1.5}>
              {moneyFields.map(([name, label]) => (
                <Grid item key={name} xs={12} sm={6}>
                  <MoneyField label={label} required fullWidth disabled={busy} value={form[name]}
                    onChange={(value) => setForm((prior) => ({ ...prior, [name]: value }))} />
                </Grid>
              ))}
              <Grid item xs={12} sm={6}>
                <TextField label="Контрольная дата поступления" type="date" required fullWidth disabled={busy}
                  InputLabelProps={{ shrink: true }} value={form.controlDate} onChange={(event) => setForm({ ...form, controlDate: event.target.value })} />
              </Grid>
              <Grid item xs={12} sm={6}>
                <TextField label="Дата платежа поставщику" type="date" required fullWidth disabled={busy}
                  InputLabelProps={{ shrink: true }} value={form.paymentDate} onChange={(event) => setForm({ ...form, paymentDate: event.target.value })} />
              </Grid>
            </Grid>
            <Button type="submit" variant="outlined" disabled={busy || !complete || !editing} sx={{ alignSelf: 'flex-start' }}>Сохранить план</Button>
          </Stack>
        )}
        {quote && !editing && (
          <>
            <Alert severity={quote.shortfall > 0 ? 'warning' : 'info'}>{describeQuote(quote)}</Alert>
            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5} useFlexGap flexWrap="wrap">
              <Typography variant="body2">Остаток после оплаты {rub(quote.projectedBalance)}</Typography>
              <Typography variant="body2">Потребность {rub(quote.requiredAmount)}</Typography>
              <Typography variant="body2">Доступный лимит {rub(quote.remainingLimit)}</Typography>
            </Stack>
            {active && !quote.drawnAmount && (
              <Stack direction={{ xs: 'column', sm: 'row' }} useFlexGap flexWrap="wrap" spacing={1}>
                <Button size="small" disabled={busy || quote.receiptStatus === 'RECEIVED'} onClick={() => recordReceipt('RECEIVED')}>Симуляция: деньги поступили</Button>
                <Button size="small" disabled={busy || quote.receiptStatus !== 'PENDING'} onClick={() => recordReceipt('DELAYED')}>Симуляция: деньги задержались</Button>
              </Stack>
            )}
            {active && quote.canDraw && (
              <Button variant="contained" disabled={busy} onClick={() => void run(() => paymentReserveApi.confirm(loanId, quote.version), true)} sx={{ alignSelf: 'flex-start' }}>
                Подтвердить выборку {rub(quote.suggestedDraw)}
              </Button>
            )}
          </>
        )}
      </Stack>
    </Box>
  );
}
