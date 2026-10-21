import { Button, Paper, Stack, Typography } from '@mui/material';
import ErrorOutlineRoundedIcon from '@mui/icons-material/ErrorOutlineRounded';

interface ErrorStateProps {
  message: string;
  onRetry?: () => void;
}

export function ErrorState({ message, onRetry }: ErrorStateProps) {
  return (
    <Paper variant="outlined" sx={{ p: 4, textAlign: 'center', borderRadius: 1 }}>
      <Stack alignItems="center" spacing={1.5}>
        <ErrorOutlineRoundedIcon color="error" fontSize="large" />
        <Typography variant="h6">Не удалось загрузить данные</Typography>
        <Typography color="text.secondary" maxWidth={520}>{message}</Typography>
        {onRetry && <Button variant="contained" onClick={onRetry}>Повторить</Button>}
      </Stack>
    </Paper>
  );
}
