import AddAPhotoOutlinedIcon from '@mui/icons-material/AddAPhotoOutlined';
import CheckRoundedIcon from '@mui/icons-material/CheckRounded';
import { Alert, Box, Button, ButtonBase, Paper, Stack, Typography } from '@mui/material';
import { useRef, useState } from 'react';
import { authApi } from '../api/client';
import { avatarPresets, isPhoto, prepareAvatarPhoto } from '../shared/avatars';
import { tokens } from '../theme/theme';
import { User } from '../types';
import { UserAvatar } from './UserAvatar';

/** Profile block: a preset illustration, one's own photo, or back to initials. Saved at once. */
export function AvatarPicker({ user, onChanged }: { user: User; onChanged: (user: User) => void }) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const file = useRef<HTMLInputElement>(null);
  const current = user.avatar ?? null;

  const save = async (avatar: string | null) => {
    setBusy(true);
    setError(null);
    try {
      onChanged(await authApi.changeAvatar(avatar));
    } catch (nextError) {
      setError(nextError instanceof Error ? nextError.message : 'Не удалось сохранить аватар');
    } finally {
      setBusy(false);
    }
  };

  const upload = async (picked: File | undefined) => {
    if (!picked) return;
    try {
      await save(await prepareAvatarPhoto(picked));
    } catch (nextError) {
      setError(nextError instanceof Error ? nextError.message : 'Не удалось обработать фото');
    }
  };

  return (
    <Paper sx={{ p: { xs: 3, md: 4 } }}>
      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={3} alignItems={{ sm: 'center' }}>
        <UserAvatar fullName={user.fullName} avatar={current} size={88} />
        <Box sx={{ minWidth: 0, flex: 1 }}>
          <Typography variant="h6">Аватар</Typography>
          <Typography color="text.secondary" sx={{ mt: 0.5 }}>
            {user.role === 'CLIENT' ? 'Виден в шапке кабинета и коллегам в списке представителей компании.' : 'Виден в шапке кабинета.'} Выберите картинку или загрузите своё фото — оно уменьшится до 160 × 160.
          </Typography>
          <Stack direction="row" spacing={1} sx={{ mt: 2 }} flexWrap="wrap" useFlexGap>
            <Button variant="outlined" startIcon={<AddAPhotoOutlinedIcon />} disabled={busy} onClick={() => file.current?.click()}>
              {isPhoto(current) ? 'Другое фото' : 'Загрузить фото'}
            </Button>
            {current && <Button variant="text" disabled={busy} onClick={() => void save(null)}>Вернуть инициалы</Button>}
          </Stack>
          <input ref={file} type="file" hidden accept="image/png,image/jpeg,image/webp" aria-label="Фото для аватара"
            onChange={(event) => { const picked = event.target.files?.[0]; event.target.value = ''; void upload(picked); }} />
        </Box>
      </Stack>
      {error && <Alert severity="error" sx={{ mt: 2 }}>{error}</Alert>}
      <Box role="radiogroup" aria-label="Готовые аватары"
        sx={{ mt: 3, display: 'grid', gap: 1.5, gridTemplateColumns: { xs: 'repeat(4, 1fr)', sm: 'repeat(6, 1fr)', md: 'repeat(12, 1fr)' } }}>
        {avatarPresets.map((preset) => {
          const value = `preset:${preset.code}`;
          const selected = current === value;
          return (
            <ButtonBase key={preset.code} role="radio" aria-checked={selected} aria-label={preset.title} title={preset.title} disabled={busy}
              onClick={() => void save(value)}
              sx={{
                position: 'relative', aspectRatio: '1', borderRadius: '50%', bgcolor: preset.background, maxWidth: 72, width: '100%', justifySelf: 'center',
                boxShadow: selected ? `0 0 0 3px #fff, 0 0 0 5px ${tokens.ink}` : 'none', transition: 'transform 0.15s ease, box-shadow 0.15s ease',
                '&:hover': { transform: 'scale(1.06)' },
                '&.Mui-focusVisible': { boxShadow: `0 0 0 3px #fff, 0 0 0 5px ${tokens.info}` },
              }}>
              <img src={`/illustrations/${preset.code}.png`} alt="" aria-hidden draggable={false} style={{ width: '68%', height: '68%', objectFit: 'contain' }} />
              {selected && (
                <Box component="span" sx={{ position: 'absolute', right: -2, bottom: -2, width: 22, height: 22, borderRadius: '50%', bgcolor: tokens.ink, color: '#fff', display: 'grid', placeItems: 'center' }}>
                  <CheckRoundedIcon sx={{ fontSize: 16 }} />
                </Box>
              )}
            </ButtonBase>
          );
        })}
      </Box>
    </Paper>
  );
}
