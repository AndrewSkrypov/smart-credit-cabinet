import { Button, ButtonProps } from '@mui/material';
import { tokens } from '../theme/theme';

/** A quiet filled action, distinct from navigation links and the primary red CTA. */
export function SecondaryActionButton({ sx, ...props }: ButtonProps) {
  return <Button {...props} variant="outlined" color="inherit" sx={[
    { bgcolor: tokens.secondaryFill, borderColor: 'transparent', fontWeight: 600, minWidth: 0, maxWidth: '100%',
      '&:hover': { bgcolor: 'rgba(15, 25, 55, 0.16)', borderColor: 'transparent' } },
    ...(Array.isArray(sx) ? sx : [sx])
  ]} />;
}
