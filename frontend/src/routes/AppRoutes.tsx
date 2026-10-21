import { Navigate, Route, Routes } from 'react-router-dom';
import { roleHome, useAuth } from '../auth/AuthProvider';
import { AppShell } from '../components/AppShell';
import { RoleGuard } from '../components/RoleGuard';
import { SignInPage, SignUpPage } from '../pages/AuthPages';
import { BankHomePage } from '../pages/BankHomePage';
import { ClientHomePage } from '../pages/ClientHomePage';
import { ApplicationPage } from '../pages/client/application/ApplicationPage';
import { NeedsWizardPage } from '../pages/client/NeedsWizardPage';
import { AccessDeniedPage, NotFoundPage } from '../pages/SystemPages';
import { bankRoles } from '../shared/labels';

function Home() {
  const { session } = useAuth();
  return <Navigate to={session ? roleHome[session.user.role] : '/sign-in'} replace />;
}

export function AppRoutes() {
  return (
    <Routes>
      <Route path="/" element={<Home />} />
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
          <Route path="/bank" element={<BankHomePage />} />
        </Route>
      </Route>
      <Route path="/access-denied" element={<AccessDeniedPage />} />
      <Route path="*" element={<NotFoundPage />} />
    </Routes>
  );
}
