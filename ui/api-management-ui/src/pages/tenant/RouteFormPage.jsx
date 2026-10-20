import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { ArrowDown, ArrowLeft, Plus, X } from 'lucide-react';
import { api } from '../../api/client.js';
import { useAuth } from '../../auth/AuthContext.jsx';
import { useToast } from '../../components/Toast.jsx';
import { Alert, Button, Field, Loading, PageHeader } from '../../components/ui.jsx';

const METHODS = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD', 'OPTIONS'];

// Content-Type and Accept are always forwarded by the gateway, so they are not listed per route
const ALWAYS_FORWARDED = ['content-type', 'accept'];

const EMPTY_ROUTE = {
  routeId: '',
  name: '',
  pathPattern: '/',
  targetBaseUrl: 'http://',
  targetPathPrefix: '',
  allowedMethods: ['GET'],
  allowedHeaders: [],
  requestsPerMinute: '',
  responseTimeoutMs: '3000',
  idempotencyEnabled: false,
  headerRules: [],
};

function toForm(route) {
  return {
    ...route,
    allowedHeaders: route.allowedHeaders ?? [],
    requestsPerMinute: route.requestsPerMinute ?? '',
    responseTimeoutMs: String(route.responseTimeoutMs),
    headerRules: route.headerRules.map((rule) => ({ ...rule, headerValue: rule.headerValue ?? '' })),
  };
}

function toRequest(form) {
  return {
    routeId: form.routeId,
    name: form.name,
    pathPattern: form.pathPattern,
    targetBaseUrl: form.targetBaseUrl,
    targetPathPrefix: form.targetPathPrefix,
    allowedMethods: form.allowedMethods,
    allowedHeaders: form.allowedHeaders,
    requestsPerMinute: form.requestsPerMinute === '' ? null : Number(form.requestsPerMinute),
    responseTimeoutMs: form.responseTimeoutMs === '' ? null : Number(form.responseTimeoutMs),
    idempotencyEnabled: form.idempotencyEnabled,
    headerRules: form.headerRules,
  };
}

// The per-user limit is not configured separately yet: one tenth of the route limit, at least 1
function perUserLimit(requestsPerMinute) {
  if (requestsPerMinute === '' || requestsPerMinute == null) return null;
  return Math.max(1, Math.floor(Number(requestsPerMinute) / 10));
}

function toRouteId(name) {
  return name.toLowerCase().trim().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 100);
}

export default function RouteFormPage() {
  const { routeId } = useParams();
  const editing = Boolean(routeId);
  const { user } = useAuth();
  const toast = useToast();
  const navigate = useNavigate();

  const [form, setForm] = useState(editing ? null : EMPTY_ROUTE);
  const [routeIdEdited, setRouteIdEdited] = useState(editing);
  const [errors, setErrors] = useState({});
  const [error, setError] = useState(null);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    if (!editing) return;
    api(`/api/tenant/routes/${routeId}`)
      .then((route) => setForm(toForm(route)))
      .catch((err) => {
        toast.error(err.message);
        navigate('/tenant/routes', { replace: true });
      });
  }, [editing, routeId, navigate, toast]);

  if (!form) return <Loading />;

  const set = (field, value) => setForm((current) => ({ ...current, [field]: value }));

  function toggleMethod(method) {
    set('allowedMethods', form.allowedMethods.includes(method)
      ? form.allowedMethods.filter((m) => m !== method)
      : METHODS.filter((m) => m === method || form.allowedMethods.includes(m)));
  }

  function updateRule(index, field, value) {
    set('headerRules', form.headerRules.map((rule, i) => (i === index ? { ...rule, [field]: value } : rule)));
  }

  async function handleSubmit(event) {
    event.preventDefault();
    setSaving(true);
    setErrors({});
    setError(null);
    try {
      const saved = await api(editing ? `/api/tenant/routes/${routeId}` : '/api/tenant/routes', {
        method: editing ? 'PUT' : 'POST',
        body: toRequest(form),
      });
      toast.success(`${saved.name} ${editing ? 'updated' : 'created'}`);
      navigate('/tenant/routes');
    } catch (err) {
      setErrors(err.fieldErrors);
      setError(Object.keys(err.fieldErrors).length ? 'Please fix the highlighted fields.' : err.message);
      setSaving(false);
      window.scrollTo({ top: 0, behavior: 'smooth' });
    }
  }

  const headerRuleErrors = Object.entries(errors).filter(([key]) => key.startsWith('headerRules'));
  const allowedHeaderError = Object.entries(errors).find(([key]) => key.startsWith('allowedHeaders'))?.[1];
  const perUser = perUserLimit(form.requestsPerMinute);

  return (
    <>
      <Link to="/tenant/routes" className="back-link"><ArrowLeft size={16} /> All routes</Link>
      <PageHeader
        title={editing ? `Edit ${form.name || routeId}` : 'New route'}
        subtitle={editing
          ? 'Saving publishes a new configuration version for your organization.'
          : 'Register one exact endpoint the gateway may forward to your backend.'}
      />

      <form className="route-form" onSubmit={handleSubmit}>
        <div className="route-form-main">
          {error && <Alert>{error}</Alert>}

          <section className="card form-section">
            <h2>Basics</h2>
            <div className="form-grid">
              <Field label="Name" error={errors.name}>
                <input
                  value={form.name}
                  onChange={(event) => {
                    set('name', event.target.value);
                    if (!routeIdEdited) set('routeId', toRouteId(event.target.value));
                  }}
                  placeholder="Get order"
                  required
                />
              </Field>
              <Field label="Route ID" error={errors.routeId} hint={editing ? 'Route IDs cannot be changed' : 'Lowercase letters, digits and hyphens'}>
                <input
                  value={form.routeId}
                  disabled={editing}
                  onChange={(event) => {
                    setRouteIdEdited(true);
                    set('routeId', event.target.value);
                  }}
                  placeholder="get-order"
                  required
                />
              </Field>
            </div>
            <Field
              label="Public path"
              error={errors.pathPattern}
              hint="One exact endpoint, e.g. /orders or /orders/{id}. {name} matches exactly one path segment; there are no wildcards."
            >
              <div className="input-prefix">
                <span>/{user.organizationSlug}</span>
                <input value={form.pathPattern} onChange={(event) => set('pathPattern', event.target.value)} placeholder="/orders/{id}" required />
              </div>
            </Field>
          </section>

          <section className="card form-section">
            <h2>Upstream</h2>
            <div className="form-grid">
              <Field label="Target base URL" error={errors.targetBaseUrl} hint="http(s)://host[:port], no path">
                <input value={form.targetBaseUrl} onChange={(event) => set('targetBaseUrl', event.target.value)} placeholder="http://orders-service:8080" required />
              </Field>
              <Field label="Target path prefix" error={errors.targetPathPrefix} hint="Optional, e.g. /api/v1">
                <input value={form.targetPathPrefix} onChange={(event) => set('targetPathPrefix', event.target.value)} placeholder="/api/v1" />
              </Field>
            </div>
            <Field label="Allowed methods" error={errors.allowedMethods}>
              <div className="method-toggles">
                {METHODS.map((method) => (
                  <button
                    type="button"
                    key={method}
                    className={`method-toggle ${form.allowedMethods.includes(method) ? 'selected' : ''}`}
                    onClick={() => toggleMethod(method)}
                    aria-pressed={form.allowedMethods.includes(method)}
                  >
                    {method}
                  </button>
                ))}
              </div>
            </Field>
            <Field
              label="Allowed request headers"
              error={allowedHeaderError}
              hint="Only these headers reach your backend; every other incoming header is dropped. Content-Type and Accept are always forwarded. Add Authorization if your backend checks it."
            >
              <HeaderChips value={form.allowedHeaders} onChange={(headers) => set('allowedHeaders', headers)} />
            </Field>
          </section>

          <section className="card form-section">
            <h2>Policies</h2>
            <div className="form-grid">
              <Field
                label="Rate limit (requests per minute)"
                error={errors.requestsPerMinute}
                hint={perUser ? `All callers together. Each user may make ${perUser}/min (a tenth of the limit).` : 'Leave empty for no limit'}
              >
                <input type="number" min="1" value={form.requestsPerMinute} onChange={(event) => set('requestsPerMinute', event.target.value)} placeholder="Unlimited" />
              </Field>
              <Field label="Response timeout (ms)" error={errors.responseTimeoutMs} hint="Between 100 and 60000">
                <input type="number" min="100" max="60000" value={form.responseTimeoutMs} onChange={(event) => set('responseTimeoutMs', event.target.value)} required />
              </Field>
            </div>
            <label className="switch-row">
              <input type="checkbox" checked={form.idempotencyEnabled} onChange={(event) => set('idempotencyEnabled', event.target.checked)} />
              <span className="switch" aria-hidden="true" />
              <span>
                <strong>Idempotency</strong>
                <span className="muted">Repeated requests with the same Idempotency-Key get the first response instead of running twice. Turn on only if your backend also deduplicates on the Idempotency-Key header: the gateway forwards it and may retry writes when a connection breaks.</span>
              </span>
            </label>
          </section>

          <section className="card form-section">
            <div className="section-heading">
              <h2>Header rules</h2>
              <Button
                type="button"
                variant="secondary"
                size="sm"
                icon={Plus}
                onClick={() => set('headerRules', [...form.headerRules, { direction: 'REQUEST', action: 'ADD', headerName: '', headerValue: '' }])}
              >
                Add rule
              </Button>
            </div>
            {headerRuleErrors.length > 0 && (
              <Alert>{headerRuleErrors.map(([key, message]) => <div key={key}>{key}: {message}</div>)}</Alert>
            )}
            {form.headerRules.length === 0 ? (
              <p className="muted">No header changes. Requests and responses pass through untouched.</p>
            ) : (
              <div className="rule-list">
                {form.headerRules.map((rule, index) => (
                  <div key={index} className="rule-row">
                    <select value={rule.direction} onChange={(event) => updateRule(index, 'direction', event.target.value)} aria-label="Direction">
                      <option value="REQUEST">Request</option>
                      <option value="RESPONSE">Response</option>
                    </select>
                    <select value={rule.action} onChange={(event) => updateRule(index, 'action', event.target.value)} aria-label="Action">
                      <option value="ADD">Add</option>
                      <option value="SET">Set</option>
                      <option value="REMOVE">Remove</option>
                    </select>
                    <input value={rule.headerName} onChange={(event) => updateRule(index, 'headerName', event.target.value)} placeholder="X-Header-Name" aria-label="Header name" required />
                    <input
                      value={rule.action === 'REMOVE' ? '' : rule.headerValue}
                      disabled={rule.action === 'REMOVE'}
                      onChange={(event) => updateRule(index, 'headerValue', event.target.value)}
                      placeholder={rule.action === 'REMOVE' ? 'Not needed' : 'Value'}
                      aria-label="Header value"
                    />
                    <button
                      type="button"
                      className="icon-button"
                      onClick={() => set('headerRules', form.headerRules.filter((_, i) => i !== index))}
                      aria-label="Remove rule"
                    >
                      <X size={16} />
                    </button>
                  </div>
                ))}
              </div>
            )}
          </section>

          <div className="form-actions">
            <Link to="/tenant/routes" className="btn btn-ghost">Cancel</Link>
            <Button type="submit" loading={saving}>{editing ? 'Save changes' : 'Create route'}</Button>
          </div>
        </div>

        <aside className="route-form-side">
          <RoutePreview form={form} slug={user.organizationSlug} />
        </aside>
      </form>
    </>
  );
}

// Shows how one example request travels through the gateway with the current settings
function RoutePreview({ form, slug }) {
  // fill every {param} with a sample value to show one concrete request
  const examplePath = (form.pathPattern || '/').replace(/\{[^/]+}/g, '42');
  const method = form.allowedMethods[0] ?? 'GET';
  const upstream = `${form.targetBaseUrl || 'http://…'}${form.targetPathPrefix}${examplePath}`;
  const perUser = perUserLimit(form.requestsPerMinute);

  return (
    <div className="card preview-card">
      <h3>Request preview</h3>
      <div className="preview-step">
        <span className="preview-label">Client calls the gateway</span>
        <code><span className={`method method-${method.toLowerCase()}`}>{method}</span> /{slug}{examplePath}</code>
      </div>
      <div className="preview-arrow"><ArrowDown size={16} /></div>
      <div className="preview-step">
        <span className="preview-label">Gateway forwards to</span>
        <code>{upstream}</code>
      </div>
      <ul className="preview-facts">
        <li><span>Methods</span><strong>{form.allowedMethods.join(', ') || 'none'}</strong></li>
        <li><span>Auth</span><strong>User token</strong></li>
        <li><span>Headers forwarded</span><strong>{['Content-Type', 'Accept', ...form.allowedHeaders].join(', ')}</strong></li>
        <li><span>Rate limit</span><strong>{form.requestsPerMinute ? `${form.requestsPerMinute} / min` : 'Unlimited'}</strong></li>
        <li><span>Per user</span><strong>{perUser ? `${perUser} / min` : '—'}</strong></li>
        <li><span>Timeout</span><strong>{form.responseTimeoutMs || '—'} ms</strong></li>
        <li><span>Idempotency</span><strong>{form.idempotencyEnabled ? 'On' : 'Off'}</strong></li>
        <li><span>Header rules</span><strong>{form.headerRules.length}</strong></li>
      </ul>
    </div>
  );
}

// Header allowlist editor: type a name and press Enter (or comma) to add it
function HeaderChips({ value, onChange }) {
  const [draft, setDraft] = useState('');

  function add() {
    const name = draft.trim().replace(/,$/, '');
    setDraft('');
    if (!name) return;
    const exists = [...value, ...ALWAYS_FORWARDED].some((header) => header.toLowerCase() === name.toLowerCase());
    if (!exists) onChange([...value, name]);
  }

  return (
    <div className="chip-input">
      {value.map((header) => (
        <span key={header} className="chip">
          {header}
          <button type="button" onClick={() => onChange(value.filter((h) => h !== header))} aria-label={`Remove ${header}`}>
            <X size={12} />
          </button>
        </span>
      ))}
      <input
        value={draft}
        onChange={(event) => setDraft(event.target.value)}
        onKeyDown={(event) => {
          if (event.key === 'Enter' || event.key === ',') {
            event.preventDefault();
            add();
          } else if (event.key === 'Backspace' && !draft && value.length) {
            onChange(value.slice(0, -1));
          }
        }}
        onBlur={add}
        placeholder={value.length ? 'Add another header' : 'e.g. Authorization, X-Correlation-Id'}
        aria-label="Add allowed header"
      />
    </div>
  );
}
