import { Alert, Box, Button, Dialog, DialogActions, DialogContent, DialogTitle, FormControlLabel, Paper, Stack, Switch, TextField, Typography } from '@mui/material';
import { FormEvent, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { authApi, notificationsApi } from '../api/client';
import { useAuth } from '../auth/AuthProvider';
import { ErrorState } from '../components/ErrorState';
import { LoadingBlock } from '../components/LoadingBlock';
import { PageHeader } from '../components/PageHeader';
import { useApi } from '../hooks/useApi';
import { emailError, nameError, passwordError, requiredError, useFieldFeedback } from '../shared/authValidation';
import { roleLabels } from '../shared/labels';
import { NotificationSettings } from '../types';

export function ProfilePage() {
  const { session, signOut, updateSession } = useAuth();
  const navigate = useNavigate();
  const loaded = useApi(notificationsApi.settings);
  const [saved, setSaved] = useState<NotificationSettings | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [currentPassword, setCurrentPassword] = useState('');
  const [password, setPassword] = useState('');
  const [securityBusy, setSecurityBusy] = useState(false);
  const [securityError, setSecurityError] = useState<string | null>(null);
  const [confirmLogout, setConfirmLogout] = useState(false);
  const [fullName, setFullName] = useState(session?.user.fullName ?? '');
  const [email, setEmail] = useState(session?.user.email ?? '');
  const [profilePassword, setProfilePassword] = useState('');
  const [profileBusy, setProfileBusy] = useState(false);
  const [profileError, setProfileError] = useState<string | null>(null);
  const [profileSaved, setProfileSaved] = useState(false);
  const settings = saved ?? loaded.data;

  const emailChanged = session ? email.trim().toLowerCase() !== session.user.email : false;
  const profileChanged = session ? emailChanged || fullName.trim().replace(/\s+/g, ' ') !== session.user.fullName : false;
  const profileErrors = {
    fullName: nameError(fullName),
    email: emailError(email),
    profilePassword: emailChanged && !profilePassword ? requiredError : ''
  };
  const profileFeedback = useFieldFeedback(profileErrors, 'profile');
  const securityErrors = {
    currentPassword: currentPassword ? '' : requiredError,
    newPassword: passwordError(password)
  };
  const securityFeedback = useFieldFeedback(securityErrors, 'security');

  if (!session) return null;

  const change = async (enabled: boolean) => {
    if (!settings || busy) return;
    setBusy(true);
    setError(null);
    try {
      setSaved(await notificationsApi.saveSettings(enabled, settings.version));
    } catch (nextError) {
      setError(nextError instanceof Error ? nextError.message : 'Не удалось сохранить настройки');
      setSaved(null);
      await loaded.reload();
    } finally {
      setBusy(false);
    }
  };

  const saveProfile = async (event: FormEvent) => {
    event.preventDefault();
    if (profileBusy || !profileChanged) return;
    if (Object.values(profileErrors).some(Boolean)) { profileFeedback.visitAll(); return; }
    setProfileBusy(true);
    setProfileError(null);
    setProfileSaved(false);
    try {
      const next = await authApi.updateProfile(fullName.trim(), email.trim(), emailChanged ? profilePassword : undefined);
      updateSession(next);
      setFullName(next.user.fullName);
      setEmail(next.user.email);
      setProfilePassword('');
      profileFeedback.resetVisited();
      setProfileSaved(true);
    } catch (next) {
      setProfileError(next instanceof Error ? next.message : 'Не удалось сохранить данные');
    } finally { setProfileBusy(false); }
  };

  const secure = async (logout: boolean, event?: FormEvent) => {
    event?.preventDefault();
    if (securityBusy) return;
    if (!logout && Object.values(securityErrors).some(Boolean)) { securityFeedback.visitAll(); return; }
    setSecurityBusy(true);
    setSecurityError(null);
    try {
      if (logout) await authApi.logoutAll();
      else await authApi.changePassword(currentPassword, password);
      setCurrentPassword('');
      setPassword('');
      signOut();
      navigate('/sign-in?security=1', { replace: true });
    } catch (next) {
      setSecurityError(next instanceof Error ? next.message : 'Не удалось изменить настройки входа');
    } finally { setSecurityBusy(false); setConfirmLogout(false); }
  };

  return (
    <>
      <PageHeader title="Профиль" subtitle="Ваши данные и настройки кабинета" />
      <Stack spacing={3} sx={{ pb: 6 }}>
        <Paper sx={{ p: { xs: 3, md: 4 } }}>
          <Typography variant="h6">Личные данные</Typography>
          <Typography color="text.secondary" sx={{ mt: 0.5, mb: 2 }}>{roleLabels[session.user.role]}</Typography>
          {session.user.role === 'AGENT' && <TextField label="ID агента" value={session.user.id} InputProps={{ readOnly: true }} fullWidth sx={{ mb: 2 }} />}
          {session.user.role === 'CLIENT' && <Box sx={{ mb: 2 }}>
            <Typography variant="body2">Подписание договоров: {session.user.canSign ? 'доступно' : 'недоступно'}</Typography>
            <Typography variant="body2" color="text.secondary">{session.user.canSign ? 'Вы можете подписывать договоры от имени компании.' : 'Договор должен подписать представитель компании с правом подписи.'}</Typography>
          </Box>}
          <Stack component="form" spacing={2} onSubmit={(event) => void saveProfile(event)} noValidate>
            {profileError && <Alert severity="error">{profileError}</Alert>}
            {profileSaved && <Alert severity="success">Данные сохранены</Alert>}
            <TextField {...profileFeedback.field('fullName', undefined, () => setFullName((value) => value.trim()))} label="Имя и фамилия" autoComplete="name" required value={fullName} inputProps={{ maxLength: 255 }}
              onChange={(event) => { setFullName(event.target.value); setProfileSaved(false); }} />
            <TextField {...profileFeedback.field('email', 'Email — логин для входа и адрес для писем', () => setEmail((value) => value.trim()))} label="Email" type="email" autoComplete="email" required value={email} inputProps={{ maxLength: 255 }}
              onChange={(event) => { setEmail(event.target.value); setProfileSaved(false); }} />
            {emailChanged && (
              <TextField {...profileFeedback.field('profilePassword')} label="Текущий пароль для смены email" type="password" autoComplete="current-password" required value={profilePassword}
                onChange={(event) => setProfilePassword(event.target.value)} />
            )}
            <Box>
              <Button type="submit" variant="contained" disabled={profileBusy || !profileChanged}>Сохранить данные</Button>
            </Box>
          </Stack>
        </Paper>
        <Paper sx={{ p: { xs: 3, md: 4 } }}>
          <Typography variant="h6" sx={{ mb: 2 }}>Уведомления</Typography>
          {loaded.loading ? <LoadingBlock /> : loaded.error ? <ErrorState message={loaded.error} onRetry={loaded.reload} /> : settings && (
            settings.emailAvailable ? (
              <Box>
                <FormControlLabel
                  control={<Switch checked={settings.emailEnabled} disabled={busy} onChange={(_, enabled) => void change(enabled)} />}
                  label="Дублировать важные уведомления на почту"
                />
                <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
                  Письмо напомнит о действии или решении. Условия и подробности останутся в кабинете.
                </Typography>
              </Box>
            ) : <Typography color="text.secondary">Почтовый канал на этом стенде выключен. Уведомления доступны в кабинете.</Typography>
          )}
          {error && <Alert severity="error" sx={{ mt: 2 }}>{error}</Alert>}
        </Paper>
        <Paper sx={{ p: { xs: 3, md: 4 } }}>
          <Typography variant="h6" sx={{ mb: 2 }}>Безопасность</Typography>
          <Stack component="form" spacing={2} onSubmit={(event) => void secure(false, event)} noValidate>
            <Typography color="text.secondary">После смены пароля потребуется снова войти на всех устройствах.</Typography>
            {securityError && <Alert severity="error">{securityError}</Alert>}
            <TextField {...securityFeedback.field('currentPassword')} label="Текущий пароль" type="password" autoComplete="current-password" required value={currentPassword} onChange={(event) => setCurrentPassword(event.target.value)} />
            <TextField {...securityFeedback.field('newPassword', 'От 8 до 72 символов, буквы и цифры')} label="Новый пароль" type="password" autoComplete="new-password" required value={password} onChange={(event) => setPassword(event.target.value)} />
            <Button type="submit" variant="contained" disabled={securityBusy}>Сменить пароль</Button>
            <Button variant="outlined" disabled={securityBusy} onClick={() => setConfirmLogout(true)}>Выйти на всех устройствах</Button>
          </Stack>
        </Paper>
      </Stack>
      <Dialog open={confirmLogout} onClose={() => !securityBusy && setConfirmLogout(false)}>
        <DialogTitle>Завершить все сессии?</DialogTitle>
        <DialogContent>Вы выйдете из кабинета на всех устройствах, включая это. Ожидающие ссылки восстановления перестанут действовать.</DialogContent>
        <DialogActions>
          <Button disabled={securityBusy} onClick={() => setConfirmLogout(false)}>Отмена</Button>
          <Button disabled={securityBusy} onClick={() => void secure(true)}>Завершить все сессии</Button>
        </DialogActions>
      </Dialog>
    </>
  );
}
