import { useState } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { Eye, EyeOff, Gauge, Lock, Route, ShieldCheck, User } from 'lucide-react';
import { canVisit, homeFor, useAuth } from '../auth/AuthContext.jsx';
import Logo from '../components/Logo.jsx';
import { Alert, Button } from '../components/ui.jsx';

const FEATURES = [
  { icon: Route, title: 'Route mappings', text: 'Map public paths to upstream services per organization.' },
  { icon: Gauge, title: 'Traffic policies', text: 'Rate limits, timeouts and idempotency, applied at the edge.' },
  { icon: ShieldCheck, title: 'Tenant isolation', text: 'Every organization manages only its own routes.' },
];

export default function LoginPage() {
  const { user, login, notice } = useAuth();
  const location = useLocation();
  const [form, setForm] = useState({ username: '', password: '' });
  const [showPassword, setShowPassword] = useState(false);
  const [error, setError] = useState(null);
  const [submitting, setSubmitting] = useState(false);

  // Signed in (just now or already): go back to the page that sent us here if this role may see it
  if (user) {
    const from = location.state?.from;
    return <Navigate to={canVisit(user, from) ? from : homeFor(user)} replace />;
  }

  async function handleSubmit(event) {
    event.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      await login(form.username.trim(), form.password);
    } catch (err) {
      setError(err.message);
      setSubmitting(false);
    }
  }

  return (
    <div className="login-page">
      <section className="login-hero">
        <div className="login-hero-brand">
          <Logo size={40} />
          <span>Gatekeeper</span>
        </div>
        <div className="login-hero-copy">
          <h1>Control every API that crosses your gateway.</h1>
          <p>One place to onboard organizations, assign their admins and publish the routes your smart gateway enforces.</p>
        </div>
        <ul className="login-features">
          {FEATURES.map(({ icon: Icon, title, text }) => (
            <li key={title}>
              <div className="login-feature-icon"><Icon size={18} /></div>
              <div>
                <strong>{title}</strong>
                <span>{text}</span>
              </div>
            </li>
          ))}
        </ul>
      </section>

      <section className="login-panel">
        <form className="login-card" onSubmit={handleSubmit}>
          <div className="login-card-header">
            <h2>Sign in</h2>
            <p>Use the account your platform administrator gave you.</p>
          </div>

          {notice && !error && <Alert tone="info">{notice}</Alert>}
          {error && <Alert>{error}</Alert>}

          <label className="field">
            <span className="field-label">Username</span>
            <div className="input-with-icon">
              <User size={16} />
              <input
                autoFocus
                autoComplete="username"
                value={form.username}
                onChange={(event) => setForm({ ...form, username: event.target.value })}
                required
              />
            </div>
          </label>

          <label className="field">
            <span className="field-label">Password</span>
            <div className="input-with-icon">
              <Lock size={16} />
              <input
                type={showPassword ? 'text' : 'password'}
                autoComplete="current-password"
                value={form.password}
                onChange={(event) => setForm({ ...form, password: event.target.value })}
                required
              />
              <button
                type="button"
                className="icon-button input-action"
                onClick={() => setShowPassword(!showPassword)}
                aria-label={showPassword ? 'Hide password' : 'Show password'}
              >
                {showPassword ? <EyeOff size={16} /> : <Eye size={16} />}
              </button>
            </div>
          </label>

          <Button type="submit" loading={submitting} className="btn-block">Sign in</Button>
          <p className="login-footnote">Accounts are created by a platform administrator. There is no self sign-up.</p>
        </form>
      </section>
    </div>
  );
}
