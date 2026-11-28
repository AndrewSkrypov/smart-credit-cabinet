import { Alert, Button, Paper, Snackbar, Stack, TextField, Typography } from '@mui/material';
import { FormEvent, useEffect, useState } from 'react';
import { applicationsApi } from '../../../api/client';
import { useAuth } from '../../../auth/AuthProvider';
import { formatDateTime } from '../../../shared/format';
import { Application } from '../../../types';
import { useApi } from '../../../hooks/useApi';
import { ErrorState } from '../../../components/ErrorState';
import { LoadingBlock } from '../../../components/LoadingBlock';
import { ContractTextPanel } from './ContractTextPanel';

/**
 * Signing with a one-time code. A real simple electronic signature sends the code by SMS; the
 * case keeps SMS out of the scenario, so the code is shown here, marked as such.
 */
export function SigningBlock({ application, onChanged }: { application: Application; onChanged: (next: Application) => void }) {
  const { session } = useAuth();
  const signing = application.process.signing;
  const contract = useApi(() => applicationsApi.contract(application.id), [application.id, signing?.expiresAt]);
  const [code, setCode] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [dismissed, setDismissed] = useState<string | null>(null);
  const [, refreshExpiry] = useState(0);
  const expiresAtText = signing?.expiresAt ?? '';
  const expiresAt = Date.parse(expiresAtText);
  const activeCode = application.status === 'SIGNING' && !signing?.signedAt && expiresAt > Date.now() ? signing?.code : null;
  const noticeKey = activeCode ? JSON.stringify([application.id, expiresAt, activeCode]) : null;
  const noticeOpen = noticeKey !== null && noticeKey !== dismissed;

  useEffect(() => {
    if (!session?.user.canSign || !activeCode) return;
    const timer = window.setTimeout(() => refreshExpiry((revision) => revision + 1), Math.min(expiresAt - Date.now(), 2_147_483_647));
    return () => window.clearTimeout(timer);
  }, [activeCode, expiresAt, session?.user.canSign]);

  if (!session?.user.canSign) {
    return (
      <Paper variant="outlined" sx={{ p: { xs: 2, md: 3 } }}>
        <Typography variant="h6" component="h2">Подписание договора</Typography>
        <Typography color="text.secondary">Договор должен подписать представитель компании с правом подписи.</Typography>
      </Paper>
    );
  }

  const act = async (call: () => Promise<Application>) => {
    setBusy(true);
    setError(null);
    try {
      onChanged(await call());
      await contract.reload();
      setCode('');
    } catch (nextError) {
      setError(nextError instanceof Error ? nextError.message : 'Не удалось выполнить действие');
    } finally {
      setBusy(false);
    }
  };

  const submit = (event: FormEvent) => {
    event.preventDefault();
    void act(() => applicationsApi.sign(application.id, code));
  };

  return (
    <Paper variant="outlined" sx={{ p: { xs: 2, md: 3 }, borderColor: 'warning.main' }}>
      <Typography variant="h6" component="h2" gutterBottom>Подписание договора</Typography>
      <Typography color="text.secondary">Прочитайте условия договора перед подписанием.</Typography>
      {contract.loading && !contract.data && <LoadingBlock />}
      {contract.error && <ErrorState message={contract.error} onRetry={contract.reload} />}
      {contract.data && <ContractTextPanel contract={contract.data} />}
      <Stack component="form" onSubmit={submit} direction={{ xs: 'column', sm: 'row' }} spacing={1.5} alignItems={{ sm: 'flex-start' }}>
        <TextField id="signing-code-input" name="signingCode" label="Код из 6 цифр" disabled={busy || !contract.data?.signable || !!contract.error} value={code} onChange={(event) => setCode(event.target.value.replace(/\D/g, '').slice(0, 6))} inputProps={{ inputMode: 'numeric', autoComplete: 'one-time-code' }} />
        <Button type="submit" variant="contained" size="large" disabled={busy || code.length !== 6 || !contract.data?.signable || !!contract.error} sx={{ minHeight: 56 }}>Подписать</Button>
        <Button type="button" variant="text" size="large" disabled={busy}
          aria-expanded={activeCode ? noticeOpen : undefined}
          onClick={() => {
            if (activeCode && expiresAt > Date.now()) setDismissed(null);
            else void act(() => applicationsApi.requestCode(application.id));
          }} sx={{ minHeight: 56 }}>
          {activeCode ? 'Показать код' : 'Получить код'}
        </Button>
      </Stack>
      {error && <Alert severity="error" sx={{ mt: 2 }}>{error}</Alert>}
      {noticeOpen && (
        <Snackbar key={noticeKey} open autoHideDuration={12000}
          sx={(theme) => ({ [theme.breakpoints.down('sm')]: { bottom: theme.spacing(9) } })}
          onClose={(_, reason) => { if (reason !== 'clickaway') setDismissed(noticeKey); }}>
          <Alert severity="info" variant="filled" closeText="Закрыть" onClose={() => setDismissed(noticeKey)}>
            <Typography variant="body2">Демо-код подписи</Typography>
            <Typography component="strong" variant="h6" sx={{ fontVariantNumeric: 'tabular-nums' }} data-testid="signing-code-popup">
              {activeCode}
            </Typography>
            <Typography variant="caption" display="block">Действует до {formatDateTime(expiresAtText)}</Typography>
            <Button type="button" color="inherit" disabled={busy} sx={{ color: 'inherit', mt: 0.5, minHeight: 40 }}
              onClick={() => void act(() => applicationsApi.requestCode(application.id))}>
              Новый код
            </Button>
          </Alert>
        </Snackbar>
      )}
    </Paper>
  );
}
