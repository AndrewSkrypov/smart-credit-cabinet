import { Box, Typography } from '@mui/material';
import { ReactNode } from 'react';

/** Keep two-dimensional data inside a named, keyboard reachable native scroller. */
export function TableScrollRegion({ label, children }: { label: string; children: ReactNode }) {
  return <>
    <Typography variant="caption" color="text.secondary" sx={{ display: { xs: 'block', sm: 'none' }, mt: 2 }}>Прокрутите таблицу вбок, чтобы увидеть все столбцы.</Typography>
    <Box role="region" aria-label={label} tabIndex={0} sx={{ minWidth: 0, maxWidth: '100%', overflowX: 'auto', mt: 1,
      '&:focus-visible': { outline: '2px solid', outlineColor: 'info.main', outlineOffset: 2 } }}>
      {children}
    </Box>
  </>;
}
