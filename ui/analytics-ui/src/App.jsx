import { Navigate, Route, Routes } from 'react-router-dom';
import { homeFor, useAuth } from './auth/AuthContext.jsx';
import RequireRole from './auth/RequireRole.jsx';
import Layout from './components/Layout.jsx';
import DashboardPage from './pages/DashboardPage.jsx';
import LivePage from './pages/LivePage.jsx';
import LoginPage from './pages/LoginPage.jsx';
import OrganizationsPage from './pages/OrganizationsPage.jsx';
import RequestLogPage from './pages/RequestLogPage.jsx';
import RoutesPage from './pages/RoutesPage.jsx';
import SystemMetricsPage from './pages/SystemMetricsPage.jsx';
import TrafficPoliciesPage from './pages/TrafficPoliciesPage.jsx';

const ADMINS = ['PLATFORM_ADMIN', 'TENANT_ADMIN'];

export default function App() {
  const { user } = useAuth();

  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />

      <Route element={<RequireRole roles={ADMINS}><Layout /></RequireRole>}>
        <Route path="/dashboard" element={<DashboardPage />} />
        <Route path="/live" element={<LivePage />} />
        <Route path="/routes" element={<RoutesPage />} />
        <Route path="/policies" element={<TrafficPoliciesPage />} />
        <Route path="/organizations" element={<RequireRole roles={['PLATFORM_ADMIN']}><OrganizationsPage /></RequireRole>} />
        <Route path="/requests" element={<RequestLogPage />} />
        <Route path="/system" element={<RequireRole roles={['PLATFORM_ADMIN']}><SystemMetricsPage /></RequireRole>} />
      </Route>

      {/* "/" and any unknown URL: go to the signed-in user's home, or to login */}
      <Route path="*" element={<Navigate to={homeFor(user)} replace />} />
    </Routes>
  );
}
