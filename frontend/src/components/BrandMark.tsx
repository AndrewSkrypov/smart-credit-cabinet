import { Box, Stack, Typography } from '@mui/material';
import { Link as RouterLink } from 'react-router-dom';
import { tokens } from '../theme/theme';

/** The cabinet's own mark: a neutral sign, not the bank's logo (a trademark). */
export function BrandMark({ to = '/', compact = false, inverted = false, label = 'Кабинет предпринимателя' }: { to?: string; compact?: boolean; inverted?: boolean; label?: string }) {
  return (
    <Stack component={RouterLink} to={to} aria-label={`${label} — на главную`} direction="row" spacing={1.5} alignItems="center"
      sx={{ color: inverted ? '#fff' : 'inherit', textDecoration: 'none' }}>
      <Box aria-hidden sx={{ width: 32, height: 32, borderRadius: '8px', bgcolor: tokens.red, display: 'grid', placeItems: 'center', color: '#fff', fontWeight: 700 }}>К</Box>
      <Typography variant="subtitle1" component="span" fontWeight={700} sx={{ display: compact ? { xs: 'none', sm: 'inline' } : 'inline' }}>
        {label}
      </Typography>
    </Stack>
  );
}
