import { Box, Button, Paper, Stack, Typography } from '@mui/material';
import InboxRoundedIcon from '@mui/icons-material/InboxRounded';

interface EmptyStateProps {
  title: string;
  description?: string;
  actionLabel?: string;
  onAction?: () => void;
}

export function EmptyState({ title, description, actionLabel, onAction }: EmptyStateProps) {
  return (
    <Paper variant="outlined" sx={{ p: 4, textAlign: 'center', borderRadius: 1 }}>
      <Stack alignItems="center" spacing={1.5}>
        <Box sx={{ color: 'text.secondary' }}><InboxRoundedIcon fontSize="large" /></Box>
        <Typography variant="h6">{title}</Typography>
        {description && <Typography color="text.secondary" maxWidth={520}>{description}</Typography>}
        {actionLabel && <Button variant="contained" onClick={onAction}>{actionLabel}</Button>}
      </Stack>
    </Paper>
  );
}
