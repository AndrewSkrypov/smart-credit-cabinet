import { Alert, Box, Button, Checkbox, FormControlLabel, Grid, Paper, Stack, TextField, Typography } from '@mui/material';
import { useCallback, useEffect, useRef, useState } from 'react';
import { applicationsApi, productsApi } from '../../../api/client';
import { MoneyField } from '../../../components/MoneyField';
import { useApi } from '../../../hooks/useApi';
import { months, rub, rubExact } from '../../../shared/format';
import { Application, ApplicationInput } from '../../../types';

type SaveState = 'saved' | 'saving' | 'dirty' | 'error';

const toInput = (application: Application): ApplicationInput => ({
  productCode: application.product.code,
  purpose: application.purpose,
  purposeComment: application.purposeComment,
  amount: application.amount,
  termMonths: application.termMonths,
  declaredRevenue12m: application.declaredRevenue12m,
  declaredMonthlyDebtPayments: application.declaredMonthlyDebtPayments,
  bureauConsent: application.bureauConsent
});

const SAVE_DELAY_MS = 700;

/**
 * Debounced writes are serialized and coalesced to the latest revision. Submission freezes
 * input and waits for that revision; an older response cannot confirm newer edits.
 */
export function DraftForm({ application, onSaved, onSubmitted }: { application: Application; onSaved: (next: Application) => void; onSubmitted: (next: Application) => void }) {
  const products = useApi(productsApi.list);
  const product = application.currentProduct ?? products.data?.find((item) => item.code === application.product.code);
  const changed = product?.version !== undefined && product.version !== application.product.version;
  const [acceptedVersion, setAcceptedVersion] = useState<number | null>(null);
  const [form, setForm] = useState<ApplicationInput>(() => toInput(application));
  const [saveState, setSaveState] = useState<SaveState>('saved');
  const [error, setError] = useState<string | null>(null);
  const [sending, setSending] = useState(false);
  const timer = useRef<number>();
  const latest = useRef(form);
  const revision = useRef(0);
  const confirmed = useRef({ revision: 0, application });
  const inFlight = useRef<Promise<Application | null> | null>(null);
  const mounted = useRef(true);
  const submitting = useRef(false);
  const callbacks = useRef({ onSaved, onSubmitted });
  callbacks.current = { onSaved, onSubmitted };
  // Document uploads can refresh validation/estimate without editing the form revision.
  if (confirmed.current.revision === revision.current && JSON.stringify(toInput(application)) === JSON.stringify(latest.current)) {
    confirmed.current.application = application;
  }

  const save = useCallback((): Promise<Application | null> => {
    window.clearTimeout(timer.current);
    if (inFlight.current) return inFlight.current;
    const drain = async () => {
      while (mounted.current && confirmed.current.revision !== revision.current) {
        const snapshotRevision = revision.current;
        const snapshot = { ...latest.current };
        setSaveState('saving');
        try {
          const saved = await applicationsApi.update(application.id, snapshot);
          if (!mounted.current) return null;
          confirmed.current = { revision: snapshotRevision, application: saved };
          if (snapshotRevision === revision.current) {
            setSaveState('saved');
            setError(null);
            callbacks.current.onSaved(saved);
          }
        } catch (nextError) {
          if (!mounted.current) return null;
          // A failed older write must not discard newer edits waiting behind it.
          if (snapshotRevision !== revision.current) continue;
          setSaveState('error');
          setError(nextError instanceof Error ? nextError.message : 'Не удалось сохранить');
          return null;
        }
      }
      return mounted.current ? confirmed.current.application : null;
    };
    const pending = drain();
    inFlight.current = pending;
    void pending.finally(() => { if (inFlight.current === pending) inFlight.current = null; });
    return pending;
  }, [application.id]);

  useEffect(() => {
    mounted.current = true;
    return () => { mounted.current = false; window.clearTimeout(timer.current); };
  }, []);

  const change = (patch: Partial<ApplicationInput>) => {
    if (submitting.current) return;
    latest.current = { ...latest.current, ...patch };
    revision.current += 1;
    setForm(latest.current);
    setSaveState('dirty');
    setError(null);
    window.clearTimeout(timer.current);
    timer.current = window.setTimeout(() => void save(), SAVE_DELAY_MS);
  };

  const submit = async () => {
    if (submitting.current || (changed && acceptedVersion !== product?.version) || product?.active === false) return;
    submitting.current = true;
    setSending(true);
    setError(null);
    try {
      const saved = await save();
      if (!saved || !mounted.current || saved.submissionProblems.length > 0) return;
      // One key per application: a repeated click or a retry returns the first result.
      const submitted = await applicationsApi.submit(application.id, `submit-${application.id}`, changed ? acceptedVersion ?? undefined : undefined);
      if (mounted.current) callbacks.current.onSubmitted(submitted);
    } catch (nextError) {
      if (!mounted.current) return;
      setError(nextError instanceof Error ? nextError.message : 'Не удалось отправить заявку');
      try {
        const refreshed = await applicationsApi.get(application.id);
        if (mounted.current) callbacks.current.onSaved(refreshed);
      } catch { /* Keep the actionable submission error when refresh is unavailable. */ }
    } finally {
      submitting.current = false;
      if (mounted.current) setSending(false);
    }
  };

  const termOutside = product && (form.termMonths < product.minTermMonths || form.termMonths > product.maxTermMonths);
  const amountOutside = product && (form.amount < product.minAmount || form.amount > product.maxAmount);

  return (
    <Paper variant="outlined" sx={{ p: { xs: 2, md: 3 } }}>
      <Stack direction="row" justifyContent="space-between" alignItems="baseline" spacing={2}>
        <Typography variant="h6" component="h2">Заявка</Typography>
        <Typography variant="body2" color={saveState === 'error' ? 'error' : 'text.secondary'} aria-live="polite">
          {{ saved: 'Сохранено', saving: 'Сохраняем…', dirty: 'Изменения сохранятся автоматически', error: 'Не сохранено' }[saveState]}
        </Typography>
      </Stack>
      <Typography color="text.secondary" sx={{ mb: 2 }}>{application.product.name} · {application.purposeTitle}</Typography>
      {product?.active === false ? <Alert severity="warning" sx={{ mb: 2 }}>Продукт выключен из каталога. Выберите другой продукт и создайте новую заявку.</Alert> : changed && product && (
        <Alert severity="warning" sx={{ mb: 2 }}>
          <Typography fontWeight={600}>Условия продукта изменились</Typography>
          <Typography>Новая версия {product.version}: {product.name}. Ставка {product.baseRate}%, сумма от {rub(product.minAmount)} до {rub(product.maxAmount)}, срок от {product.minTermMonths} до {product.maxTermMonths} месяцев. {product.revolving ? 'Возобновляемый лимит.' : 'Разовая выдача.'}</Typography>
          <Typography>{product.description}</Typography>
          <FormControlLabel control={<Checkbox disabled={sending} checked={acceptedVersion === product.version} onChange={(_, checked) => setAcceptedVersion(checked ? product.version ?? null : null)} />} label="Проверил новые условия продукта и согласен отправить заявку по ним" />
        </Alert>
      )}

      <Box component="fieldset" disabled={sending} sx={{ border: 0, p: 0, m: 0, minWidth: 0 }}>
      <Grid container spacing={2}>
        <Grid item xs={12} sm={6}>
          <MoneyField label="Сумма" fullWidth value={form.amount} onChange={(value) => change({ amount: value ?? 0 })}
            error={!!amountOutside} helperText={product ? `от ${rub(product.minAmount)} до ${rub(product.maxAmount)}` : ' '} />
        </Grid>
        <Grid item xs={12} sm={6}>
          <TextField label="Срок, месяцев" fullWidth inputProps={{ inputMode: 'numeric' }} value={form.termMonths || ''}
            onChange={(event) => change({ termMonths: Number(event.target.value.replace(/\D/g, '').slice(0, 3)) || 0 })}
            error={!!termOutside}
            helperText={product ? (product.minTermMonths === product.maxTermMonths ? `лимит на ${months(product.maxTermMonths)}` : `от ${product.minTermMonths} до ${product.maxTermMonths}`) : ' '} />
        </Grid>
        <Grid item xs={12}>
          <TextField label="На что именно пойдут деньги" fullWidth multiline minRows={2} inputProps={{ maxLength: 1000 }}
            value={form.purposeComment ?? ''} onChange={(event) => change({ purposeComment: event.target.value })}
            helperText="Необязательно, но помогает банку быстрее принять решение" />
        </Grid>
        <Grid item xs={12} sm={6}>
          <MoneyField label="Выручка за последние 12 месяцев" fullWidth required value={form.declaredRevenue12m ?? null}
            onChange={(value) => change({ declaredRevenue12m: value })} helperText="Как в налоговой отчётности — банк сверит её с данными ФНС" />
        </Grid>
        <Grid item xs={12} sm={6}>
          <MoneyField label="Платежи по кредитам в других банках, в месяц" fullWidth value={form.declaredMonthlyDebtPayments ?? null}
            onChange={(value) => change({ declaredMonthlyDebtPayments: value })} helperText="0, если кредитов нет" />
        </Grid>
        <Grid item xs={12}>
          <FormControlLabel
            control={<Checkbox checked={form.bureauConsent} onChange={(event) => change({ bureauConsent: event.target.checked })} />}
            label="Согласен на запрос кредитной истории компании в бюро кредитных историй"
          />
        </Grid>
      </Grid>
      </Box>

      {application.estimate && (
        <Box sx={{ mt: 2, p: 2, bgcolor: 'action.hover', borderRadius: 1 }}>
          <Typography variant="body2" color="text.secondary">Ориентировочный платёж по базовой ставке {application.estimate.rate}%</Typography>
          <Typography variant="h6">{rubExact(application.estimate.monthlyPayment)} в месяц</Typography>
        </Box>
      )}

      {application.submissionProblems.length > 0 && (
        <Alert severity="info" sx={{ mt: 2 }}>
          Чтобы отправить заявку:
          <ul style={{ margin: '4px 0 0', paddingLeft: 20 }}>
            {application.submissionProblems.map((problem) => <li key={problem}>{problem}</li>)}
          </ul>
        </Alert>
      )}
      {error && <Alert severity="error" sx={{ mt: 2 }}>{error}</Alert>}

      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5} sx={{ mt: 2 }}>
        <Button variant="contained" size="large" onClick={submit} disabled={sending || (changed && acceptedVersion !== product?.version) || product?.active === false || (saveState === 'saved' && application.submissionProblems.length > 0)}>
          {sending ? 'Отправляем…' : 'Отправить в банк'}
        </Button>
      </Stack>
    </Paper>
  );
}
