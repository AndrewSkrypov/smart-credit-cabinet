import { Box, Container, Tab, Tabs } from '@mui/material';
import { useRef } from 'react';
import { Link as RouterLink, Outlet, useLocation } from 'react-router-dom';
import { roleHome, useAuth } from '../auth/AuthProvider';
import { bankRoles } from '../shared/labels';
import { nightGlow, tokens } from '../theme/theme';
import { DocumentSurface } from './DocumentSurface';
import { BrandMark } from './BrandMark';
import { AssistantPanel } from './AssistantPanel';
import { NotificationBell } from './NotificationBell';
import { ProfileMenu } from './ProfileMenu';
import { KeshaProvider } from './kesha/KeshaProvider';
import { OnboardingHeaderMenu, OnboardingProvider, OnboardingWelcomeBanner } from './onboarding/OnboardingTour';

/** Sections of the bank workspace; the rules are changed by the risk administrator, read by all staff. */
const bankSections = [
  { path: '/bank', label: 'Заявки' },
  { path: '/bank/analytics', label: 'Аналитика' },
  { path: '/bank/rules', label: 'Правила' },
  { path: '/bank/products', label: 'Продукты' }
];

const clientSections = [
  { path: '/client', label: 'Заявки' },
  { path: '/client/employees', label: 'Сотрудники' },
  { path: '/client/agent-mandates', label: 'Агенты' }
];

const agentSections = [
  { path: '/agent', label: 'Портфель' }
];

// Light tabs on the dark band: the current section is a soft pill, the others are muted.
const darkTabs = {
  minHeight: 44,
  '& .MuiTabs-indicator': { display: 'none' },
  '& .MuiTabs-flexContainer': { gap: 0.5 },
  '& .MuiTab-root': {
    color: 'rgba(255, 255, 255, 0.64)', minHeight: 36, minWidth: 0, my: 0.5, px: 2, py: 0.75, borderRadius: '99px', fontWeight: 500, fontSize: 15,
    transition: 'background-color 0.2s ease, color 0.2s ease',
    '&:hover': { color: '#fff', bgcolor: 'rgba(255, 255, 255, 0.06)' },
  },
  '& .MuiTab-root.Mui-selected': { color: '#fff', bgcolor: 'rgba(255, 255, 255, 0.14)' },
  '& .MuiTab-root.Mui-focusVisible': { outline: `3px solid ${tokens.info}`, outlineOffset: -3 },
} as const;

/**
 * Frame of every signed-in page, in the look of the landing: a dark band with the floating header
 * on top, and the page itself on a light sheet with large rounded corners below it.
 */
export function AppShell() {
  const { session, signOut } = useAuth();
  const location = useLocation();
  const tourRootRef = useRef<HTMLDivElement>(null);
  if (!session) return null;
  const { user } = session;
  const bank = bankRoles.includes(user.role);
  const agent = user.role === 'AGENT';
  const sections = bank ? bankSections : agent ? agentSections : clientSections;
  const section = [...sections].reverse().find((item) => location.pathname.startsWith(item.path))?.path ?? false;
  const tabs = (sx: object) => (
    <Tabs data-tour="nav-tabs" value={section} variant="scrollable" sx={{ ...darkTabs, ...sx }} aria-label={bank ? 'Разделы банка' : agent ? 'Разделы агента' : 'Разделы компании'}>
      {sections.map((item) => <Tab key={item.path} value={item.path} label={item.label} component={RouterLink} to={item.path} />)}
    </Tabs>
  );
  return (
    <OnboardingProvider targetRootRef={tourRootRef}>
    <KeshaProvider key={`${user.role}:${user.id}`}>
    <DocumentSurface kind="workspace"><Box ref={tourRootRef} sx={{ minHeight: '100vh', display: 'flex', flexDirection: 'column', background: nightGlow, overflowX: 'clip' }}>
      <Box component="header" sx={{ position: 'sticky', top: 0, zIndex: 10, pt: { xs: 1, md: 2 } }}>
        <Container maxWidth="lg">
          <Box sx={{ display: 'flex', alignItems: 'center', gap: { xs: 0.5, sm: 1, md: 1.25 }, minHeight: { xs: 56, md: 64 }, pl: { xs: 1.5, md: 2.5 }, pr: { xs: 0.75, md: 1.5 },
            borderRadius: '99px', bgcolor: 'rgba(28, 28, 30, 0.72)', backdropFilter: 'blur(16px)', border: '1px solid rgba(255, 255, 255, 0.08)', color: '#fff' }}>
            <BrandMark to={roleHome[user.role]} compact inverted label={bank ? 'Рабочее место банка' : agent ? 'Кабинет агента' : 'Кабинет предпринимателя'} />
            {tabs({ display: { xs: 'none', lg: 'flex' }, flexGrow: 1, minWidth: 0 })}
            <Box sx={{ flexGrow: 1, display: { lg: 'none' } }} />
            <OnboardingHeaderMenu />
            <Box data-tour="notifications-bell" sx={{ color: '#fff', '& .MuiIconButton-root': { color: '#fff' } }}><NotificationBell /></Box>
            <ProfileMenu user={user} onSignOut={signOut} />
          </Box>
        </Container>
      </Box>
      <Container maxWidth="lg" sx={{ display: { lg: 'none' }, mt: 1 }}>{tabs({})}</Container>
      <Box sx={{ height: { xs: 16, md: 32 } }} />
      <Box sx={{ flexGrow: 1, bgcolor: 'background.default', borderRadius: { xs: '28px 28px 0 0', md: '48px 48px 0 0' } }}>
        <Container component="main" maxWidth="lg" sx={{ py: { xs: 3, md: 5 } }}>
          <OnboardingWelcomeBanner />
          <Outlet />
        </Container>
      </Box>
      {!bank && <AssistantPanel />}
    </Box></DocumentSurface>
    </KeshaProvider>
    </OnboardingProvider>
  );
}
