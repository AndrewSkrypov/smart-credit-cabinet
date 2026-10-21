import { AppBar, Box, Button, Container, Stack, Toolbar, Typography } from '@mui/material';
import { Link as RouterLink, Outlet } from 'react-router-dom';
import { roleHome, useAuth } from '../auth/AuthProvider';
import { roleLabels } from '../shared/labels';

/** Frame of every signed-in page: product name, who is signed in, sign-out. */
export function AppShell() {
  const { session, signOut } = useAuth();
  if (!session) return null;
  const { user } = session;
  return (
    <Box sx={{ minHeight: '100vh', bgcolor: 'background.default' }}>
      <AppBar position="sticky" color="primary" elevation={0}>
        <Toolbar sx={{ gap: 2 }}>
          <Typography component={RouterLink} to={roleHome[user.role]} variant="h6" sx={{ color: 'inherit', textDecoration: 'none', fontWeight: 700, flexGrow: 1 }}>
            Кабинет предпринимателя
          </Typography>
          <Stack sx={{ display: { xs: 'none', sm: 'flex' }, textAlign: 'right' }}>
            <Typography variant="body2" fontWeight={600}>{user.fullName}</Typography>
            <Typography variant="caption" sx={{ opacity: 0.8 }}>{roleLabels[user.role]}</Typography>
          </Stack>
          <Button color="inherit" variant="outlined" onClick={signOut}>Выйти</Button>
        </Toolbar>
      </AppBar>
      <Container component="main" maxWidth="lg" sx={{ py: { xs: 3, md: 4 } }}>
        <Outlet />
      </Container>
    </Box>
  );
}
