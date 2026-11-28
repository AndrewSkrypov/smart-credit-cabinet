import { Alert, Button, Stack } from '@mui/material';

export function DraftConflictAlert({ busy, canOverwrite, reviewRequired = false, onResolve }: {
  busy: boolean;
  canOverwrite: boolean;
  reviewRequired?: boolean;
  onResolve: (overwrite: boolean) => void;
}) {
  return (
    <Alert severity="warning" sx={{ mb: 2 }}>
      {reviewRequired
        ? 'Заявка изменилась после сохранения. Загрузите свежие условия и проверьте их перед повторной отправкой.'
        : 'Черновик изменён на другом устройстве или другим сотрудником. Ваши изменения ещё не сохранены.'}
      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1} sx={{ mt: 1 }}>
        <Button size="small" disabled={busy} onClick={() => onResolve(false)}>Загрузить с сервера</Button>
        {!reviewRequired && (
          <Button size="small" variant="outlined" disabled={busy || !canOverwrite} onClick={() => onResolve(true)}>Сохранить поверх</Button>
        )}
      </Stack>
    </Alert>
  );
}
