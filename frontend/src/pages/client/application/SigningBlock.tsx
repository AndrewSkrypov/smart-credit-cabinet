import { Alert, Box, Button, Paper, Stack, TextField, Typography } from '@mui/material';
import { FormEvent, useState } from 'react';
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

  const expired = signing?.expiresAt ? new Date(signing.expiresAt).getTime() < Date.now() : true;

  return (
    <Paper variant="outlined" sx={{ p: { xs: 2, md: 3 }, borderColor: 'warning.main' }}>
      <Typography variant="h6" component="h2" gutterBottom>Подписание договора</Typography>
      <Typography color="text.secondary">Прочитайте сохранённые условия перед вводом кода. Новый код относится к этому же тексту.</Typography>
      {contract.loading && !contract.data && <LoadingBlock />}
      {contract.error && <ErrorState message={contract.error} onRetry={contract.reload} />}
      {contract.data && <ContractTextPanel contract={contract.data} />}
      {signing?.code && !expired ? (
        <Box sx={{ p: 2, mb: 2, borderRadius: 1, bgcolor: 'action.hover' }}>
          <Typography variant="body2" color="text.secondary">Код подтверждения (в реальном банке он пришёл бы в SMS)</Typography>
          <Typography variant="h4" component="p" sx={{ letterSpacing: '0.2em', fontWeight: 700 }} data-testid="signing-code">{signing.code}</Typography>
          <Typography variant="body2" color="text.secondary">Действует до {formatDateTime(signing.expiresAt!)}</Typography>
        </Box>
      ) : (
        <Alert severity="info" sx={{ mb: 2 }}>Получите код подтверждения, чтобы подписать договор.</Alert>
      )}
      <Stack component="form" onSubmit={submit} direction={{ xs: 'column', sm: 'row' }} spacing={1.5} alignItems={{ sm: 'flex-start' }}>
        <TextField id="signing-code-input" name="signingCode" label="Код из 6 цифр" disabled={busy || !contract.data?.signable || !!contract.error} value={code} onChange={(event) => setCode(event.target.value.replace(/\D/g, '').slice(0, 6))} inputProps={{ inputMode: 'numeric', autoComplete: 'one-time-code' }} />
        <Button type="submit" variant="contained" size="large" disabled={busy || code.length !== 6 || !contract.data?.signable || !!contract.error} sx={{ minHeight: 56 }}>Подписать</Button>
        <Button variant="text" size="large" disabled={busy} onClick={() => act(() => applicationsApi.requestCode(application.id))} sx={{ minHeight: 56 }}>
          {signing?.code && !expired ? 'Новый код' : 'Получить код'}
        </Button>
      </Stack>
      {error && <Alert severity="error" sx={{ mt: 2 }}>{error}</Alert>}
    </Paper>
  );
}
