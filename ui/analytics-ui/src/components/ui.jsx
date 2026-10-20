import { LoaderCircle } from 'lucide-react';

export function Button({ variant = 'primary', size, loading = false, icon: Icon, children, className = '', ...props }) {
  return (
    <button
      className={`btn btn-${variant} ${size === 'sm' ? 'btn-sm' : ''} ${className}`}
      disabled={loading || props.disabled}
      {...props}
    >
      {loading ? <LoaderCircle size={16} className="spin" /> : Icon && <Icon size={16} />}
      {children && <span>{children}</span>}
    </button>
  );
}

export function PageHeader({ title, subtitle, actions }) {
  return (
    <header className="page-header">
      <div>
        <h1>{title}</h1>
        {subtitle && <p>{subtitle}</p>}
      </div>
      {actions && <div className="page-actions">{actions}</div>}
    </header>
  );
}

export function Field({ label, hint, error, children }) {
  return (
    <label className={`field ${error ? 'field-invalid' : ''}`}>
      <span className="field-label">{label}</span>
      {children}
      {error ? <span className="field-error">{error}</span> : hint && <span className="field-hint">{hint}</span>}
    </label>
  );
}

export function Badge({ tone = 'neutral', children }) {
  return <span className={`badge badge-${tone}`}>{children}</span>;
}

export function Alert({ tone = 'error', children }) {
  return <div className={`alert alert-${tone}`}>{children}</div>;
}

export function EmptyState({ icon: Icon, title, text, action }) {
  return (
    <div className="empty-state">
      {Icon && <div className="empty-icon"><Icon size={22} /></div>}
      <h3>{title}</h3>
      {text && <p>{text}</p>}
      {action}
    </div>
  );
}

export function Loading({ label = 'Loading…' }) {
  return (
    <div className="loading">
      <LoaderCircle size={20} className="spin" />
      <span>{label}</span>
    </div>
  );
}

export function formatDate(value) {
  if (!value) return '—';
  return new Date(value).toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' });
}

export function formatDateTime(value) {
  if (!value) return '—';
  return new Date(value).toLocaleString(undefined, {
    day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit',
  });
}
