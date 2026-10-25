import { Box, Stack, Tooltip, Typography } from '@mui/material';
import { tokens } from '../theme/theme';

interface HBarProps {
  label: string;
  value: number;
  max: number;
  /** The number as it is read next to the bar, e.g. "14 / 20" or "37 заявок". */
  valueText: string;
  /** Longer text shown on hover and to screen readers. */
  detail?: string;
}

/**
 * One horizontal bar of a single-series chart: a recessive track, a dark fill anchored at the
 * start with a rounded data end, the label and the number in text ink. One series, so no legend.
 */
export function HBar({ label, value, max, valueText, detail }: HBarProps) {
  const share = max > 0 ? Math.max(0, Math.min(1, value / max)) : 0;
  return (
    <Tooltip title={detail ?? `${label}: ${valueText}`} placement="top-start" disableInteractive>
      <Box role="img" aria-label={`${label}: ${valueText}${detail ? `. ${detail}` : ''}`} sx={{ py: 0.75, cursor: 'default' }}>
        <Stack direction="row" justifyContent="space-between" alignItems="baseline" flexWrap="wrap" useFlexGap spacing={2} sx={{ mb: 0.5 }}>
          <Typography variant="body2" sx={{ minWidth: 0, flex: '1 1 140px', overflowWrap: 'anywhere' }}>{label}</Typography>
          <Typography variant="body2" fontWeight={500} sx={{ maxWidth: '100%', overflowWrap: 'anywhere' }}>{valueText}</Typography>
        </Stack>
        <Box sx={{ height: 8, borderRadius: '4px', bgcolor: tokens.secondaryFill }}>
          <Box sx={{ width: `${share * 100}%`, minWidth: value > 0 ? 4 : 0, height: '100%', borderRadius: '4px', bgcolor: tokens.dark }} />
        </Box>
      </Box>
    </Tooltip>
  );
}
