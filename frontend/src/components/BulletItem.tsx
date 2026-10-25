import { Box } from '@mui/material';
import { ReactNode } from 'react';

/** A grid gap does not reset the marker's first-line alignment like Stack.spacing. */
export function BulletItem({ children, color }: { children: ReactNode; color: string }) {
  return <Box component="li" sx={{ display: 'grid', gridTemplateColumns: '8px minmax(0, 1fr)', columnGap: 1.5, lineHeight: 1.5, '& .MuiTypography-root': { lineHeight: 'inherit' } }}>
    <Box aria-hidden sx={{ width: 8, height: 8, borderRadius: '50%', bgcolor: color, mt: 'calc((1lh - 8px) / 2)' }} />
    {children}
  </Box>;
}
