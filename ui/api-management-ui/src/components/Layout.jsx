import { NavLink, Outlet } from 'react-router-dom';
import { Building, History, KeyRound, LayoutDashboard, LogOut, Route, Users } from 'lucide-react';
import { roleLabel, useAuth } from '../auth/AuthContext.jsx';
import Logo from './Logo.jsx';

const NAVIGATION = {
  PLATFORM_ADMIN: [
    { to: '/platform', label: 'Overview', icon: LayoutDashboard, end: true },
    { to: '/platform/organizations', label: 'Organizations', icon: Building },
    { to: '/platform/tenant-admins', label: 'Tenant admins', icon: Users },
  ],
  TENANT_ADMIN: [
    { to: '/tenant/routes', label: 'Routes', icon: Route },
    { to: '/tenant/token-settings', label: 'Token settings', icon: KeyRound },
    { to: '/tenant/history', label: 'History', icon: History },
  ],
};

export default function Layout() {
  const { user, logout } = useAuth();
  const initials = user.username.slice(0, 2).toUpperCase();

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="sidebar-brand">
          <Logo />
          <div>
            <strong>Gatekeeper</strong>
            <span>API Management</span>
          </div>
        </div>

        {user.organizationName && (
          <div className="sidebar-org">
            <span>Organization</span>
            <strong>{user.organizationName}</strong>
          </div>
        )}

        <nav className="sidebar-nav">
          {(NAVIGATION[user.role] ?? []).map(({ to, label, icon: Icon, end }) => (
            <NavLink key={to} to={to} end={end} className={({ isActive }) => `nav-link ${isActive ? 'active' : ''}`}>
              <Icon size={18} />
              <span>{label}</span>
            </NavLink>
          ))}
        </nav>

        <div className="sidebar-user">
          <div className="avatar">{initials}</div>
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
        <Outlet />
      </main>
    </div>
  );
}
