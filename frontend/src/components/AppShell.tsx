import { AppBar, Box, Button, Container, Stack, Toolbar, Typography } from '@mui/material';
import { Link as RouterLink, Outlet } from 'react-router-dom';
import { roleHome, useAuth } from '../auth/AuthProvider';
import { roleLabels } from '../shared/labels';
import { tokens } from '../theme/theme';

/** Frame of every signed-in page: product name, who is signed in, sign-out. */
export function AppShell() {
  const { session, signOut } = useAuth();
  if (!session) return null;
  const { user } = session;
  return (
    <Box sx={{ minHeight: '100vh', bgcolor: 'background.default' }}>
      <AppBar position="sticky" color="inherit" elevation={0}>
        <Toolbar sx={{ gap: 2, minHeight: { xs: 64, md: 72 } }}>
          <Stack component={RouterLink} to={roleHome[user.role]} aria-label="Кабинет предпринимателя — на главную" direction="row" spacing={1.5} alignItems="center" sx={{ color: 'inherit', textDecoration: 'none', flexGrow: 1 }}>
            <Box aria-hidden sx={{ width: 32, height: 32, borderRadius: '8px', bgcolor: tokens.red, display: 'grid', placeItems: 'center', color: '#fff', fontWeight: 700 }}>К</Box>
            <Typography variant="subtitle1" component="span" fontWeight={700} sx={{ display: { xs: 'none', sm: 'inline' } }}>Кабинет предпринимателя</Typography>
          </Stack>
          <Stack sx={{ display: { xs: 'none', sm: 'flex' }, textAlign: 'right' }}>
            <Typography variant="body2" fontWeight={500}>{user.fullName}</Typography>
            <Typography variant="caption" color="text.secondary">{roleLabels[user.role]}</Typography>
          </Stack>
          <Button variant="outlined" onClick={signOut}>Выйти</Button>
        </Toolbar>
      </AppBar>
      <Container component="main" maxWidth="lg" sx={{ py: { xs: 3, md: 5 } }}>
        <Outlet />
      </Container>
    </Box>
  );
}
