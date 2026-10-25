import { Box, Stack, Typography } from '@mui/material';
import { Link as RouterLink } from 'react-router-dom';
import { tokens } from '../theme/theme';

/** The bank's letter on a dark square: a red «А» over a red bar, as on its business pages. */
export function BrandMark({ to = '/', compact = false, inverted = false, label = 'Кабинет предпринимателя' }: { to?: string; compact?: boolean; inverted?: boolean; label?: string }) {
  return (
    <Stack component={RouterLink} to={to} aria-label={`${label} — на главную`} direction="row" spacing={1.5} alignItems="center"
      sx={{ color: inverted ? '#fff' : 'inherit', textDecoration: 'none', minWidth: 0 }}>
      <Box aria-hidden sx={{ width: 36, height: 36, flexShrink: 0, borderRadius: '8px', bgcolor: tokens.night, color: tokens.red,
        border: inverted ? '1px solid rgba(255, 255, 255, 0.14)' : 'none',
        display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center' }}>
        <Box component="span" sx={{ fontFamily: 'Arial, Helvetica, sans-serif', fontWeight: 700, fontSize: 22, lineHeight: 1 }}>А</Box>
        <Box component="span" sx={{ width: 15, height: 3, mt: '2px', bgcolor: tokens.red }} />
      </Box>
      <Typography variant="subtitle1" component="span" fontWeight={700} sx={{ minWidth: 0, overflowWrap: 'anywhere', display: compact ? { xs: 'none', sm: 'inline' } : 'inline' }}>
        {label}
      </Typography>
    </Stack>
  );
}
