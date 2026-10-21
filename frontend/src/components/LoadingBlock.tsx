import { Box, CircularProgress } from '@mui/material';

export function LoadingBlock() {
  return (
    <Box role="status" sx={{ minHeight: 280, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
      <CircularProgress aria-label="Загрузка" />
    </Box>
  );
}
