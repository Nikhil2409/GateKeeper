import { NavLink, Outlet } from 'react-router-dom';
import { Activity, Building, FileSearch, FlaskConical, Gauge, LayoutDashboard, LogOut, Radio, Route } from 'lucide-react';
import { USE_MOCK_DATA } from '../api/client.js';
import { roleLabel, useAuth } from '../auth/AuthContext.jsx';
import { FiltersProvider } from './Filters.jsx';
import Logo from './Logo.jsx';

const NAVIGATION = [
  { to: '/dashboard', label: 'Dashboard', icon: LayoutDashboard },
  { to: '/live', label: 'Live performance', icon: Radio },
  { to: '/routes', label: 'Routes', icon: Route },
  { to: '/policies', label: 'Traffic policies', icon: Gauge },
  { to: '/organizations', label: 'Organizations', icon: Building, platformOnly: true },
  { to: '/requests', label: 'Request log', icon: FileSearch },
  { to: '/system', label: 'System metrics', icon: Activity, platformOnly: true },
];

export default function Layout() {
  const { user, logout } = useAuth();
  const isPlatform = user.role === 'PLATFORM_ADMIN';

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="sidebar-brand">
          <Logo />
          <div>
            <strong>Gatekeeper</strong>
            <span>Analytics</span>
          </div>
        </div>

        {user.organizationName && (
          <div className="sidebar-org">
            <span>Organization</span>
            <strong>{user.organizationName}</strong>
          </div>
        )}

        <nav className="sidebar-nav">
          {NAVIGATION.filter((item) => isPlatform || !item.platformOnly).map(({ to, label, icon: Icon }) => (
            <NavLink key={to} to={to} className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}>
              <Icon size={18} />
              <span>{label}</span>
            </NavLink>
          ))}
        </nav>

        <div className="sidebar-user">
          <div className="avatar">{user.username.slice(0, 2).toUpperCase()}</div>
          <div className="sidebar-user-text">
            <strong>{user.username}</strong>
            <span>{roleLabel(user.role)}</span>
          </div>
          <button className="icon-button" onClick={() => logout()} title="Sign out" aria-label="Sign out">
            <LogOut size={18} />
          </button>
        </div>
      </aside>

      <main className="content">
        {USE_MOCK_DATA && (
          <div className="mock-banner">
            <FlaskConical size={16} />
            <span>Showing sample data. analytic-service doesn&apos;t expose its API yet; set <code>VITE_USE_MOCK_DATA=false</code> once it does.</span>
          </div>
        )}
        <FiltersProvider>
          <Outlet />
        </FiltersProvider>
      </main>
    </div>
  );
}
