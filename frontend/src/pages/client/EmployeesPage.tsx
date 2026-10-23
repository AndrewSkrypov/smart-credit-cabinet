import { Alert, Box, Button, Checkbox, Chip, Dialog, DialogActions, DialogContent, DialogTitle, FormControlLabel, Paper, Stack, TextField, Typography } from '@mui/material';
import { FormEvent, useRef, useState } from 'react';
import { employeesApi } from '../../api/client';
import { useAuth } from '../../auth/AuthProvider';
import { ErrorState } from '../../components/ErrorState';
import { LoadingBlock } from '../../components/LoadingBlock';
import { PageHeader } from '../../components/PageHeader';
import { useApi } from '../../hooks/useApi';
import { CompanyInvitation } from '../../types';

const invitationLabels = { PENDING: 'Ожидает принятия', ACCEPTED: 'Принято', REVOKED: 'Отменено', EXPIRED: 'Срок истёк' };
type Action = { kind: 'revoke' | 'cancel'; id: string; name: string };

export function EmployeesPage() {
  const { session, signOut } = useAuth();
  const loaded = useApi(employeesApi.list);
  const [email, setEmail] = useState('');
  const [canSign, setCanSign] = useState(false);
  const [inviteKey, setInviteKey] = useState(() => crypto.randomUUID());
  const keys = useRef(new Map<string, string>());
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [link, setLink] = useState<string | null>(null);
  const [confirm, setConfirm] = useState<Action | null>(null);
  const [copied, setCopied] = useState(false);
  const data = loaded.data;
  const keyFor = (operation: string) => {
    const existing = keys.current.get(operation) ?? crypto.randomUUID();
    keys.current.set(operation, existing);
    return existing;
  };
  const showInvitation = (invitation: CompanyInvitation) => {
    setLink(invitation.link);
    setCopied(false);
    setNotice(invitation.link ? 'Приглашение создано. Передайте ссылку сотруднику: после закрытия страницы она больше не будет показана.' : 'Приглашение создано. Если почта включена, письмо появится после доставки. Повтор запроса не показывает ссылку снова; создайте новую через повторную отправку.');
  };
  const invite = async (event: FormEvent) => {
    event.preventDefault();
    if (busy) return;
    setBusy(true); setError(null); setNotice(null); setLink(null);
    try {
      showInvitation(await employeesApi.invite(email, canSign, inviteKey));
      setEmail(''); setCanSign(false); setInviteKey(crypto.randomUUID());
      await loaded.reload();
    } catch (next) { setError(next instanceof Error ? next.message : 'Не удалось создать приглашение'); }
    finally { setBusy(false); }
  };
  const resend = async (id: string) => {
    if (busy) return;
    setBusy(true); setError(null); setNotice(null); setLink(null);
    const operation = `resend:${id}`;
    try {
      showInvitation(await employeesApi.resend(id, keyFor(operation)));
      keys.current.delete(operation);
      await loaded.reload();
    } catch (next) { setError(next instanceof Error ? next.message : 'Не удалось заменить приглашение'); }
    finally { setBusy(false); }
  };
  const apply = async () => {
    if (!confirm || busy) return;
    setBusy(true); setError(null); setNotice(null); setLink(null);
    const operation = `${confirm.kind}:${confirm.id}`;
    try {
      const response = await employeesApi[confirm.kind](confirm.id, keyFor(operation));
      keys.current.delete(operation);
      setNotice(response.message);
      if (confirm.kind === 'revoke' && confirm.id === session?.user.id) { signOut(); return; }
      await loaded.reload();
    } catch (next) { setError(next instanceof Error ? next.message : 'Не удалось изменить доступ'); }
    finally { setBusy(false); setConfirm(null); }
  };
  return (
    <>
      <PageHeader title="Сотрудники" subtitle="Представители вашей компании и приглашения в кабинет" />
      <Stack spacing={3} sx={{ pb: 6 }}>
        {error && <Alert severity="error">{error}</Alert>}
        {notice && <Alert severity="success">{notice}</Alert>}
        {link && (
          <Paper sx={{ p: { xs: 3, md: 4 } }}>
            <Stack spacing={2}>
              <Typography variant="h6">Одноразовая ссылка-приглашение</Typography>
              <Typography color="text.secondary">Ссылка действует 72 часа. Храните её как пароль. Для новой ссылки замените приглашение.</Typography>
              <TextField label="Ссылка-приглашение" value={link} InputProps={{ readOnly: true }} multiline />
              <Button variant="outlined" onClick={() => { void navigator.clipboard.writeText(link).then(() => setCopied(true)).catch(() => setError('Не удалось скопировать. Выделите ссылку и скопируйте вручную.')); }}>
                {copied ? 'Ссылка скопирована' : 'Скопировать ссылку'}
              </Button>
              <Button onClick={() => setLink(null)}>Скрыть ссылку</Button>
            </Stack>
          </Paper>
        )}
        {loaded.loading && !data ? <LoadingBlock /> : loaded.error ? <ErrorState message={loaded.error} onRetry={loaded.reload} /> : data && (
          <>
            {data.canManage ? (
              <Paper sx={{ p: { xs: 3, md: 4 } }}>
                <Typography variant="h6" sx={{ mb: 2 }}>Пригласить сотрудника</Typography>
                <Stack component="form" spacing={2} onSubmit={invite}>
                  <TextField label="Email сотрудника" type="email" required value={email} onChange={(event) => { setEmail(event.target.value); setInviteKey(crypto.randomUUID()); }} />
                  <FormControlLabel control={<Checkbox checked={canSign} onChange={(_, checked) => { setCanSign(checked); setInviteKey(crypto.randomUUID()); }} />} label="Разрешить подписание от компании" />
                  <Typography variant="body2" color="text.secondary">{data.mailAvailable ? 'Приглашение придёт сотруднику по почте.' : 'Почта выключена. После создания вы один раз увидите ссылку для передачи сотруднику.'}</Typography>
                  <Button type="submit" variant="contained" disabled={busy}>Отправить приглашение</Button>
                </Stack>
              </Paper>
            ) : <Alert severity="info">Приглашать сотрудников и отзывать доступ может представитель с правом подписи.</Alert>}
            <Box>
              <Typography variant="h6" sx={{ mb: 2 }}>Представители компании</Typography>
              <Stack spacing={2}>
                {data.members.map((member) => (
                  <Paper key={member.id} sx={{ p: 3 }}>
                    <Stack spacing={1.5}>
                      <Typography fontWeight={600}>{member.fullName}{member.id === session?.user.id ? ' (вы)' : ''}</Typography>
                      <Typography sx={{ overflowWrap: 'anywhere' }}>{member.email}</Typography>
                      <Stack direction="row" flexWrap="wrap" gap={1}>
                        <Chip size="small" label={member.status === 'ACTIVE' ? 'Доступ открыт' : 'Доступ отозван'} />
                        <Chip size="small" label={member.canSign ? 'С правом подписи' : 'Без права подписи'} />
                      </Stack>
                      {data.canManage && member.status === 'ACTIVE' && <Button variant="outlined" disabled={busy} onClick={() => setConfirm({ kind: 'revoke', id: member.id, name: member.fullName })}>Отозвать доступ</Button>}
                    </Stack>
                  </Paper>
                ))}
              </Stack>
            </Box>
            <Box>
              <Typography variant="h6" sx={{ mb: 2 }}>Приглашения</Typography>
              <Stack spacing={2}>
                {data.invitations.length === 0 && <Paper sx={{ p: 3 }}><Typography color="text.secondary">Приглашений пока нет.</Typography></Paper>}
                {data.invitations.map((invitation) => (
                  <Paper key={invitation.id} sx={{ p: 3 }}>
                    <Stack spacing={1.5}>
                      <Typography fontWeight={600} sx={{ overflowWrap: 'anywhere' }}>{invitation.email}</Typography>
                      <Typography variant="body2">{invitationLabels[invitation.status]} · {invitation.canSign ? 'С правом подписи' : 'Без права подписи'}</Typography>
                      <Typography variant="body2" color="text.secondary">Срок: {new Date(invitation.expiresAt).toLocaleString('ru-RU')}</Typography>
                      {data.canManage && ['PENDING', 'EXPIRED'].includes(invitation.status) && (
                        <Stack spacing={1}>
                          <Button variant="outlined" disabled={busy} onClick={() => void resend(invitation.id)}>Отправить повторно</Button>
                          <Button disabled={busy} onClick={() => setConfirm({ kind: 'cancel', id: invitation.id, name: invitation.email })}>Отменить приглашение</Button>
                        </Stack>
                      )}
                    </Stack>
                  </Paper>
                ))}
              </Stack>
            </Box>
          </>
        )}
      </Stack>
      <Dialog open={Boolean(confirm)} onClose={() => !busy && setConfirm(null)}>
        <DialogTitle>{confirm?.kind === 'revoke' ? 'Отозвать доступ?' : 'Отменить приглашение?'}</DialogTitle>
        <DialogContent sx={{ overflowWrap: 'anywhere' }}>{confirm?.name}. {confirm?.kind === 'revoke' ? 'Все сессии сотрудника завершатся сразу. Доступ последнего подписанта отозвать нельзя.' : 'Эта ссылка больше не будет действовать.'}</DialogContent>
        <DialogActions>
          <Button disabled={busy} onClick={() => setConfirm(null)}>Отмена</Button>
          <Button disabled={busy} onClick={() => void apply()}>Подтвердить</Button>
        </DialogActions>
      </Dialog>
    </>
  );
}
