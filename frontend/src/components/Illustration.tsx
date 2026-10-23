import { Box, SxProps, Theme } from '@mui/material';

/**
 * A 3D illustration from `public/illustrations` (Fluent Emoji 3D, MIT — see THIRD_PARTY_NOTICES).
 * Decorative: the text next to it always says the same thing.
 */
export function Illustration({ name, size = 96, sx }: { name: string; size?: number | Record<string, number>; sx?: SxProps<Theme> }) {
  return (
    <Box component="img" src={`/illustrations/${name}.png`} alt="" aria-hidden loading="lazy" draggable={false}
      sx={[{ width: size, height: size, objectFit: 'contain', userSelect: 'none', flexShrink: 0 }, ...(Array.isArray(sx) ? sx : [sx])]} />
  );
}
