import { AppBar, Box, Button, Container, Stack, Tab, Tabs, Toolbar, Typography } from '@mui/material';
import { Link as RouterLink, Outlet, useLocation } from 'react-router-dom';
import { roleHome, useAuth } from '../auth/AuthProvider';
import { roleLabels } from '../shared/labels';
import { tokens } from '../theme/theme';
import { BrandMark } from './BrandMark';
import { AssistantPanel } from './AssistantPanel';
import { NotificationBell } from './NotificationBell';

/** Sections of the bank workspace; the rules are changed by the risk administrator, read by all staff. */
const bankSections = [
  { path: '/bank', label: 'Заявки' },
  { path: '/bank/analytics', label: 'Аналитика' },
  { path: '/bank/rules', label: 'Правила' }
];

/** Frame of every signed-in page: product name, who is signed in, sign-out. */
export function AppShell() {
  const { session, signOut } = useAuth();
  const location = useLocation();
  if (!session) return null;
  const { user } = session;
  const bank = user.role !== 'CLIENT';
  const section = [...bankSections].reverse().find((item) => location.pathname.startsWith(item.path))?.path ?? false;
  return (
    <Box sx={{ minHeight: '100vh', bgcolor: 'background.default' }}>
      <AppBar position="sticky" color="inherit" elevation={0} sx={{ borderBottom: `1px solid ${tokens.line}` }}>
        <Container maxWidth="lg" disableGutters>
        <Toolbar sx={{ gap: 2, minHeight: { xs: 64, md: 72 } }}>
          <Box sx={{ flexGrow: 1 }}>
            <BrandMark to={roleHome[user.role]} compact />
          </Box>
          <Stack sx={{ display: { xs: 'none', sm: 'flex' }, textAlign: 'right' }}>
            <Typography variant="body2" fontWeight={500}>{user.fullName}</Typography>
            <Typography variant="caption" color="text.secondary">{roleLabels[user.role]}</Typography>
          </Stack>
          <NotificationBell />
          <Button variant="outlined" onClick={signOut}>Выйти</Button>
        </Toolbar>
        {bank && (
          <Tabs value={section} variant="scrollable" sx={{ px: { xs: 1, md: 2 } }} aria-label="Разделы банка">
            {bankSections.map((item) => <Tab key={item.path} value={item.path} label={item.label} component={RouterLink} to={item.path} sx={{ minHeight: 48 }} />)}
          </Tabs>
        )}
        </Container>
      </AppBar>
      <Container component="main" maxWidth="lg" sx={{ py: { xs: 3, md: 5 } }}>
        <Outlet />
      </Container>
      {!bank && <AssistantPanel />}
    </Box>
  );
}
