import { Route, Routes } from 'react-router-dom';
import { AppShell } from '../components/AppShell';
import { RoleGuard } from '../components/RoleGuard';
import { SignInPage, SignUpPage } from '../pages/AuthPages';
import { AnalyticsPage } from '../pages/bank/AnalyticsPage';
import { BankApplicationPage } from '../pages/bank/application/BankApplicationPage';
import { BankQueuePage } from '../pages/bank/BankQueuePage';
import { RulesPage } from '../pages/bank/RulesPage';
import { LandingPage } from '../pages/landing/LandingPage';
import { ClientHomePage } from '../pages/ClientHomePage';
import { ApplicationPage } from '../pages/client/application/ApplicationPage';
import { NeedsWizardPage } from '../pages/client/NeedsWizardPage';
import { AccessDeniedPage, NotFoundPage } from '../pages/SystemPages';
import { bankRoles } from '../shared/labels';

export function AppRoutes() {
  return (
    <Routes>
      <Route path="/" element={<LandingPage />} />
      <Route path="/sign-in" element={<SignInPage />} />
      <Route path="/sign-up" element={<SignUpPage />} />
      <Route element={<RoleGuard roles={['CLIENT']} />}>
        <Route element={<AppShell />}>
          <Route path="/client" element={<ClientHomePage />} />
          <Route path="/client/new" element={<NeedsWizardPage />} />
          <Route path="/client/applications/:id" element={<ApplicationPage />} />
        </Route>
      </Route>
      <Route element={<RoleGuard roles={bankRoles} />}>
        <Route element={<AppShell />}>
          <Route path="/bank" element={<BankQueuePage />} />
          <Route path="/bank/applications/:id" element={<BankApplicationPage />} />
          <Route path="/bank/analytics" element={<AnalyticsPage />} />
          <Route path="/bank/rules" element={<RulesPage />} />
        </Route>
      </Route>
      <Route path="/access-denied" element={<AccessDeniedPage />} />
      <Route path="*" element={<NotFoundPage />} />
    </Routes>
  );
}
