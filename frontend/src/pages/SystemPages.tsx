import { DocumentSurface } from '../components/DocumentSurface';
import { Box, Button, Stack, Typography } from '@mui/material';
import { Link as RouterLink } from 'react-router-dom';
import { usePageTitle } from '../hooks/usePageTitle';

function Message({ title, text }: { title: string; text: string }) {
  usePageTitle(title);
  return (
    <DocumentSurface kind="light"><Box sx={{ bgcolor: 'background.default', display: 'grid', placeItems: 'center', minHeight: '100vh', p: 3 }}>
      <Stack spacing={2} alignItems="center" sx={{ maxWidth: 480, textAlign: 'center' }}>
        <Typography variant="h4" component="h1">{title}</Typography>
        <Typography color="text.secondary">{text}</Typography>
        <Button variant="contained" component={RouterLink} to="/">На главную</Button>
      </Stack>
    </Box></DocumentSurface>
  );
}

export function NotFoundPage() {
  return <Message title="Страница не найдена" text="Проверьте адрес или вернитесь на главную." />;
}

export function AccessDeniedPage() {
  return <Message title="Нет доступа" text="Этот раздел недоступен для вашей роли." />;
}
