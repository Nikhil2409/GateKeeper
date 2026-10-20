import { Navigate, Route, Routes } from 'react-router-dom';
import { homeFor, useAuth } from './auth/AuthContext.jsx';
import RequireRole from './auth/RequireRole.jsx';
import Layout from './components/Layout.jsx';
import LoginPage from './pages/LoginPage.jsx';
import PlatformOverviewPage from './pages/platform/PlatformOverviewPage.jsx';
import OrganizationsPage from './pages/platform/OrganizationsPage.jsx';
import KafkaPage from './pages/platform/KafkaPage.jsx';
import TenantAdminsPage from './pages/platform/TenantAdminsPage.jsx';
import RoutesPage from './pages/tenant/RoutesPage.jsx';
import RouteFormPage from './pages/tenant/RouteFormPage.jsx';
import TokenSettingsPage from './pages/tenant/TokenSettingsPage.jsx';
import ConfigurationHistoryPage from './pages/tenant/ConfigurationHistoryPage.jsx';

export default function App() {
  const { user } = useAuth();

  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />

      <Route path="/platform" element={<RequireRole role="PLATFORM_ADMIN"><Layout /></RequireRole>}>
        <Route index element={<PlatformOverviewPage />} />
        <Route path="organizations" element={<OrganizationsPage />} />
        <Route path="tenant-admins" element={<TenantAdminsPage />} />
        <Route path="kafka" element={<KafkaPage />} />
      </Route>

      <Route path="/tenant" element={<RequireRole role="TENANT_ADMIN"><Layout /></RequireRole>}>
        <Route index element={<Navigate to="routes" replace />} />
        <Route path="routes" element={<RoutesPage />} />
        <Route path="routes/new" element={<RouteFormPage />} />
        <Route path="routes/:routeId/edit" element={<RouteFormPage />} />
        <Route path="token-settings" element={<TokenSettingsPage />} />
        <Route path="history" element={<ConfigurationHistoryPage />} />
      </Route>

      {/* "/" and any unknown URL: go to the signed-in user's home, or to login */}
      <Route path="*" element={<Navigate to={homeFor(user)} replace />} />
    </Routes>
  );
}
