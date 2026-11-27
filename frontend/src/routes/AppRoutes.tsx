import { Navigate, Route, Routes } from 'react-router-dom';
import { demoAccess } from '../auth/demoAccess';
import { AppShell } from '../components/AppShell';
import { RoleGuard } from '../components/RoleGuard';
import { SignInPage, SignUpPage } from '../pages/AuthPages';
import { ProfilePage } from '../pages/ProfilePage';
import { AcceptInvitationPage, PasswordRecoveryPage, PasswordResetPage } from '../pages/AccountAccessPages';
import { AgentMandatesPage } from '../pages/client/AgentMandatesPage';
import { EmployeesPage } from '../pages/client/EmployeesPage';
import { AnalyticsPage } from '../pages/bank/AnalyticsPage';
import { BankApplicationPage } from '../pages/bank/application/BankApplicationPage';
import { BankQueuePage } from '../pages/bank/BankQueuePage';
import { RulesPage } from '../pages/bank/RulesPage';
import { ProductsPage } from '../pages/bank/ProductsPage';
import { LandingPage } from '../pages/landing/LandingPage';
import { AppDownloadPage } from '../pages/AppDownloadPage';
import { ClientHomePage } from '../pages/ClientHomePage';
import { ApplicationPage } from '../pages/client/application/ApplicationPage';
import { NeedsWizardPage } from '../pages/client/NeedsWizardPage';
import { AgentPortfolioPage } from '../pages/agent/AgentPortfolioPage';
import { AgentClientPage } from '../pages/agent/AgentClientPage';
import { AgentApplicationPage } from '../pages/agent/AgentApplicationPage';
import { AccessDeniedPage, NotFoundPage } from '../pages/SystemPages';
import { bankRoles } from '../shared/labels';

export function AppRoutes() {
  return (
    <Routes>
      <Route path="/" element={demoAccess.basename ? <Navigate to="/sign-in" replace /> : <LandingPage />} />
      <Route path="/app" element={<AppDownloadPage />} />
      <Route path="/sign-in" element={<SignInPage />} />
      <Route path="/sign-up" element={<SignUpPage />} />
      <Route path="/password-recovery" element={<PasswordRecoveryPage />} />
      <Route path="/reset-password" element={<PasswordResetPage />} />
      <Route path="/accept-invitation" element={<AcceptInvitationPage />} />
      <Route element={<RoleGuard roles={['CLIENT', 'AGENT', ...bankRoles]} />}>
        <Route element={<AppShell />}>
          <Route path="/profile" element={<ProfilePage />} />
        </Route>
      </Route>
      <Route element={<RoleGuard roles={['CLIENT']} />}>
        <Route element={<AppShell />}>
          <Route path="/client" element={<ClientHomePage />} />
          <Route path="/client/new" element={<NeedsWizardPage />} />
          <Route path="/client/employees" element={<EmployeesPage />} />
          <Route path="/client/agent-mandates" element={<AgentMandatesPage />} />
          <Route path="/client/applications/:id" element={<ApplicationPage />} />
        </Route>
      </Route>
      <Route element={<RoleGuard roles={['AGENT']} />}>
        <Route element={<AppShell />}>
          <Route path="/agent" element={<AgentPortfolioPage />} />
          <Route path="/agent/clients/:companyId" element={<AgentClientPage />} />
          <Route path="/agent/applications/:id" element={<AgentApplicationPage />} />
        </Route>
      </Route>
      <Route element={<RoleGuard roles={bankRoles} />}>
        <Route element={<AppShell />}>
          <Route path="/bank" element={<BankQueuePage />} />
          <Route path="/bank/applications/:id" element={<BankApplicationPage />} />
          <Route path="/bank/analytics" element={<AnalyticsPage />} />
          <Route path="/bank/rules" element={<RulesPage />} />
          <Route path="/bank/products" element={<ProductsPage />} />
        </Route>
      </Route>
      <Route path="/access-denied" element={<AccessDeniedPage />} />
      <Route path="*" element={<NotFoundPage />} />
    </Routes>
  );
}
