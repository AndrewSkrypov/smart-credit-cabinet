import { Avatar, SxProps, Theme } from '@mui/material';
import { isPhoto, presetOf } from '../shared/avatars';
import { tokens } from '../theme/theme';

export function initials(fullName: string) {
  const parts = fullName.trim().split(/\s+/).filter(Boolean);
  return (parts.length > 1 ? parts[0][0] + parts[1][0] : (parts[0] ?? '?').slice(0, 2)).toLocaleUpperCase('ru-RU');
}

/** A person's picture: their photo, a preset illustration on a tile, or initials on the brand red. */
export function UserAvatar({ fullName, avatar, size = 36, sx }: { fullName: string; avatar?: string | null; size?: number; sx?: SxProps<Theme> }) {
  const preset = presetOf(avatar);
  const base = { width: size, height: size, fontSize: Math.round(size * 0.38), fontWeight: 600 };
  if (isPhoto(avatar)) return <Avatar src={avatar} alt="" sx={[base, ...(Array.isArray(sx) ? sx : [sx])]} />;
  if (preset) {
    return (
      <Avatar alt="" sx={[{ ...base, bgcolor: preset.background }, ...(Array.isArray(sx) ? sx : [sx])]}>
        <img src={`/illustrations/${preset.code}.png`} alt="" aria-hidden draggable={false} style={{ width: '72%', height: '72%', objectFit: 'contain' }} />
      </Avatar>
    );
  }
  return <Avatar sx={[{ ...base, bgcolor: tokens.red, color: '#fff' }, ...(Array.isArray(sx) ? sx : [sx])]}>{initials(fullName)}</Avatar>;
}
