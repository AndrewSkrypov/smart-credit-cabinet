import LogoutRoundedIcon from '@mui/icons-material/LogoutRounded';
import ManageAccountsOutlinedIcon from '@mui/icons-material/ManageAccountsOutlined';
import { Avatar, Box, ButtonBase, Divider, ListItemIcon, Menu, MenuItem, Typography } from '@mui/material';
import { useState } from 'react';
import { Link as RouterLink } from 'react-router-dom';
import { roleLabels } from '../shared/labels';
import { tokens } from '../theme/theme';
import { User } from '../types';

export function initials(fullName: string) {
  const parts = fullName.trim().split(/\s+/).filter(Boolean);
  return (parts.length > 1 ? parts[0][0] + parts[1][0] : (parts[0] ?? '?').slice(0, 2)).toLocaleUpperCase('ru-RU');
}

/**
 * One avatar instead of the name, role, profile link and sign-out in a row: the header stays light
 * on half a screen, and signing out takes a deliberate second click.
 */
export function ProfileMenu({ user, onSignOut }: { user: User; onSignOut: () => void }) {
  const [anchor, setAnchor] = useState<HTMLElement | null>(null);
  const open = anchor !== null;
  const close = () => setAnchor(null);
  return (
    <>
      <ButtonBase
        data-tour="profile-btn"
        aria-label="Меню профиля"
        aria-haspopup="menu"
        aria-expanded={open}
        aria-controls={open ? 'profile-menu' : undefined}
        title={`${user.fullName} · ${roleLabels[user.role]}`}
        onClick={(event) => setAnchor(event.currentTarget)}
        sx={{
          display: 'flex', alignItems: 'center', gap: 1.25, borderRadius: '99px', p: 0.5, pr: { xs: 0.5, md: 1.75 }, color: '#fff', flexShrink: 0, minWidth: 0,
          bgcolor: open ? 'rgba(255, 255, 255, 0.12)' : 'transparent', transition: 'background-color 0.2s ease',
          '&:hover': { bgcolor: 'rgba(255, 255, 255, 0.08)' },
          '&.Mui-focusVisible': { outline: `3px solid ${tokens.info}`, outlineOffset: 2 },
        }}
      >
        <Avatar sx={{ width: 36, height: 36, fontSize: 14, fontWeight: 600, bgcolor: tokens.red, color: '#fff' }}>{initials(user.fullName)}</Avatar>
        <Box sx={{ display: { xs: 'none', md: 'block' }, textAlign: 'left', minWidth: 0, maxWidth: 160 }}>
          <Typography variant="body2" fontWeight={500} noWrap>{user.fullName.split(/\s+/).slice(0, 2).join(' ')}</Typography>
          <Typography variant="caption" sx={{ color: 'rgba(255, 255, 255, 0.64)', display: 'block', lineHeight: 1.2 }} noWrap>{roleLabels[user.role]}</Typography>
        </Box>
      </ButtonBase>
      <Menu
        id="profile-menu"
        anchorEl={anchor}
        open={open}
        onClose={close}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'right' }}
        transformOrigin={{ vertical: 'top', horizontal: 'right' }}
        slotProps={{ paper: { sx: { mt: 1, minWidth: 260 } } }}
      >
        <Box sx={{ px: 2, pt: 1, pb: 1.5 }}>
          <Typography fontWeight={600}>{user.fullName}</Typography>
          <Typography variant="body2" color="text.secondary">{roleLabels[user.role]}</Typography>
          <Typography variant="caption" color="text.secondary" sx={{ overflowWrap: 'anywhere' }}>{user.email}</Typography>
        </Box>
        <Divider sx={{ my: 0.5 }} />
        <MenuItem component={RouterLink} to="/profile" onClick={close}>
          <ListItemIcon><ManageAccountsOutlinedIcon fontSize="small" /></ListItemIcon>
          Профиль
        </MenuItem>
        <MenuItem onClick={() => { close(); onSignOut(); }}>
          <ListItemIcon><LogoutRoundedIcon fontSize="small" /></ListItemIcon>
          Выйти
        </MenuItem>
      </Menu>
    </>
  );
}
