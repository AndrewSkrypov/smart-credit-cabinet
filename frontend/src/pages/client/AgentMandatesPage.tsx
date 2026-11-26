import { Alert, Button, Chip, Dialog, DialogActions, DialogContent, DialogTitle, Paper, Stack, TextField, Typography } from '@mui/material';
import { FormEvent, useState } from 'react';
import { agentMandatesApi, AgentMandate } from '../../api/financing';
import { useAuth } from '../../auth/AuthProvider';
import { EmptyState } from '../../components/EmptyState';
import { ErrorState } from '../../components/ErrorState';
import { LoadingBlock } from '../../components/LoadingBlock';
import { PageHeader } from '../../components/PageHeader';
import { useApi } from '../../hooks/useApi';
import { formatDateTime } from '../../shared/format';

const statusLabels = { ACTIVE: 'Действует', EXPIRED: 'Срок истёк', REVOKED: 'Отозван' };
const uuid = /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i;

export function AgentMandatesPage() {
  const { session } = useAuth();
  const canManage = session?.user.canSign ?? false;
  const mandates = useApi(agentMandatesApi.list);
  const [agentUserId, setAgentUserId] = useState('');
  const [expiresDate, setExpiresDate] = useState('');
  const [toRevoke, setToRevoke] = useState<AgentMandate | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  async function grant(event: FormEvent) {
    event.preventDefault();
    if (busy) return;
    const expiry = new Date(`${expiresDate}T23:59:59+03:00`);
    if (!uuid.test(agentUserId.trim()) || !Number.isFinite(expiry.getTime()) || expiry.getTime() <= Date.now()) {
      setError('Укажите ID агента и будущую дату окончания полномочий.');
      return;
    }
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      await agentMandatesApi.grant(agentUserId.trim(), expiry.toISOString());
      setNotice('Полномочия выданы.');
      setAgentUserId('');
      setExpiresDate('');
      await mandates.reload();
    } catch (next) {
      setError(next instanceof Error ? next.message : 'Не удалось выдать полномочия');
    } finally {
      setBusy(false);
    }
  }

  async function revoke() {
    if (!toRevoke || busy) return;
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      await agentMandatesApi.revoke(toRevoke.id);
      setToRevoke(null);
      setNotice('Полномочия отозваны. Переданные заявки доступны в разделе «Заявки».');
      await mandates.reload();
    } catch (next) {
      setError(next instanceof Error ? next.message : 'Не удалось отозвать полномочия');
      setToRevoke(null);
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <PageHeader title="Полномочия агентов" subtitle="Кому ваша компания разрешила сопровождать кредитные заявки" />
      <Stack spacing={2}>
        {error && <Alert severity="error">{error}</Alert>}
        {notice && <Alert severity="success">{notice}</Alert>}
        {mandates.loading && !mandates.data && <LoadingBlock />}
        {mandates.error && <ErrorState message={mandates.error} onRetry={mandates.reload} />}
        {!mandates.error && mandates.data?.length === 0 && <EmptyState title="Полномочий пока нет" />}
        {!mandates.error && mandates.data?.map((mandate) => (
          <Paper key={mandate.id} variant="outlined" sx={{ p: 2.5 }}>
            <Stack direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between" spacing={2}>
              <Stack spacing={0.5}>
                <Typography fontWeight={600}>{mandate.agentName}</Typography>
                <Typography variant="body2" color="text.secondary">
                  Выданы {formatDateTime(mandate.assignedAt)}{mandate.expiresAt ? ` · До ${formatDateTime(mandate.expiresAt)}` : ''}
                </Typography>
                {mandate.revokedAt && <Typography variant="body2" color="text.secondary">Отозваны {formatDateTime(mandate.revokedAt)}</Typography>}
              </Stack>
              <Stack direction="row" alignItems="center" spacing={1} flexWrap="wrap" useFlexGap>
                <Chip size="small" label={statusLabels[mandate.status]} color={mandate.status === 'ACTIVE' ? 'success' : 'default'} />
                {canManage && mandate.status === 'ACTIVE' && <Button color="error" onClick={() => setToRevoke(mandate)}>Отозвать</Button>}
                {canManage && mandate.status !== 'ACTIVE' && <Button onClick={() => setAgentUserId(mandate.agentUserId)}>Выдать заново</Button>}
              </Stack>
            </Stack>
          </Paper>
        ))}
        {canManage && (
          <Paper variant="outlined" sx={{ p: 2.5 }}>
            <Stack component="form" onSubmit={(event) => void grant(event)} spacing={2}>
              <Typography variant="h6" component="h2">Выдать полномочия</Typography>
              <TextField label="ID агента" required value={agentUserId} onChange={(event) => setAgentUserId(event.target.value)} helperText="Возьмите идентификатор в профиле агента." />
              <TextField type="date" label="Дата окончания (мск)" required value={expiresDate} onChange={(event) => setExpiresDate(event.target.value)} InputLabelProps={{ shrink: true }} />
              <Button type="submit" variant="contained" disabled={busy} sx={{ alignSelf: 'flex-start' }}>Выдать полномочия</Button>
            </Stack>
          </Paper>
        )}
      </Stack>
      <Dialog open={!!toRevoke} onClose={() => { if (!busy) setToRevoke(null); }}>
        <DialogTitle>Отозвать полномочия?</DialogTitle>
        <DialogContent>{toRevoke?.agentName} потеряет доступ к сопровождению заявок вашей компании.</DialogContent>
        <DialogActions>
          <Button disabled={busy} onClick={() => setToRevoke(null)}>Отмена</Button>
          <Button disabled={busy} color="error" onClick={() => void revoke()}>Отозвать</Button>
        </DialogActions>
      </Dialog>
    </>
  );
}
