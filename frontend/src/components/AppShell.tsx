import { Box, Button, Container, IconButton, Stack, Tab, Tabs, Tooltip, Typography } from '@mui/material';
import AccountCircleOutlinedIcon from '@mui/icons-material/AccountCircleOutlined';
import { Link as RouterLink, Outlet, useLocation } from 'react-router-dom';
import { roleHome, useAuth } from '../auth/AuthProvider';
import { roleLabels } from '../shared/labels';
import { nightGlow, tokens } from '../theme/theme';
import { DocumentSurface } from './DocumentSurface';
import { BrandMark } from './BrandMark';
import { AssistantPanel } from './AssistantPanel';
import { NotificationBell } from './NotificationBell';

/** Sections of the bank workspace; the rules are changed by the risk administrator, read by all staff. */
const bankSections = [
  { path: '/bank', label: 'Заявки' },
  { path: '/bank/analytics', label: 'Аналитика' },
  { path: '/bank/rules', label: 'Правила' },
  { path: '/bank/products', label: 'Продукты' }
];

const clientSections = [
  { path: '/client', label: 'Заявки' },
  { path: '/client/employees', label: 'Сотрудники' }
];

// Light tabs on the dark band; the current one is marked by the brand's red line.
const darkTabs = {
  minHeight: 48,
  '& .MuiTab-root': { color: 'rgba(255, 255, 255, 0.72)', minHeight: 48, fontWeight: 500 },
  '& .MuiTab-root.Mui-selected': { color: '#fff' },
  '& .MuiTabs-indicator': { backgroundColor: tokens.red, height: 3, borderRadius: 3 }
} as const;

/**
 * Frame of every signed-in page, in the look of the landing: a dark band with the floating header
 * on top, and the page itself on a light sheet with large rounded corners below it.
 */
export function AppShell() {
  const { session, signOut } = useAuth();
  const location = useLocation();
  if (!session) return null;
  const { user } = session;
  const bank = user.role !== 'CLIENT';
  const sections = bank ? bankSections : clientSections;
  const section = [...sections].reverse().find((item) => location.pathname.startsWith(item.path))?.path ?? false;
  const tabs = (sx: object) => (
    <Tabs value={section} variant="scrollable" sx={{ ...darkTabs, ...sx }} aria-label={bank ? 'Разделы банка' : 'Разделы компании'}>
      {sections.map((item) => <Tab key={item.path} value={item.path} label={item.label} component={RouterLink} to={item.path} />)}
    </Tabs>
  );
  return (
    <DocumentSurface kind="workspace"><Box sx={{ minHeight: '100vh', display: 'flex', flexDirection: 'column', background: nightGlow }}>
      <Box component="header" sx={{ position: 'sticky', top: 0, zIndex: 10, pt: { xs: 1, md: 2 } }}>
        <Container maxWidth="lg">
          <Box sx={{ display: 'flex', alignItems: 'center', gap: { xs: 1, md: 3 }, minHeight: { xs: 56, md: 64 }, pl: { xs: 1.5, md: 2.5 }, pr: { xs: 1, md: 1.5 },
            borderRadius: '99px', bgcolor: 'rgba(28, 28, 30, 0.72)', backdropFilter: 'blur(16px)', border: '1px solid rgba(255, 255, 255, 0.08)', color: '#fff' }}>
            <BrandMark to={roleHome[user.role]} compact inverted label={bank ? 'Рабочее место банка' : 'Кабинет предпринимателя'} />
            {tabs({ display: { xs: 'none', lg: 'flex' }, flexGrow: 1 })}
            <Box sx={{ flexGrow: 1, display: { lg: 'none' } }} />
            <Stack sx={{ display: { xs: 'none', sm: 'flex' }, textAlign: 'right', minWidth: 0 }}>
              <Typography variant="body2" fontWeight={500} noWrap>{user.fullName}</Typography>
              <Typography variant="caption" sx={{ color: 'rgba(255, 255, 255, 0.64)' }} noWrap>{roleLabels[user.role]}</Typography>
            </Stack>
            <Tooltip title="Профиль">
              <IconButton component={RouterLink} to="/profile" aria-label="Профиль" sx={{ color: '#fff' }}><AccountCircleOutlinedIcon /></IconButton>
            </Tooltip>
            <Box sx={{ color: '#fff', '& .MuiIconButton-root': { color: '#fff' } }}><NotificationBell /></Box>
            <Button onClick={signOut} sx={{ borderRadius: '99px', paddingInline: 3, bgcolor: tokens.nightButton, color: '#fff', '&:hover': { bgcolor: '#3a3a3f' } }}>Выйти</Button>
          </Box>
        </Container>
      </Box>
      <Container maxWidth="lg" sx={{ display: { lg: 'none' }, mt: 1 }}>{tabs({})}</Container>
      <Box sx={{ height: { xs: 16, md: 32 } }} />
      <Box sx={{ flexGrow: 1, bgcolor: 'background.default', borderRadius: { xs: '28px 28px 0 0', md: '48px 48px 0 0' } }}>
        <Container component="main" maxWidth="lg" sx={{ py: { xs: 3, md: 5 } }}>
          <Outlet />
        </Container>
      </Box>
      {!bank && <AssistantPanel />}
    </Box></DocumentSurface>
  );
}
