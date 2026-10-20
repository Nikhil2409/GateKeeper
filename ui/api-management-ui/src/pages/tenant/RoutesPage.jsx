import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { ArrowRight, Pencil, Plus, Power, Route, Search } from 'lucide-react';
import { ApiError, api } from '../../api/client.js';
import { useAuth } from '../../auth/AuthContext.jsx';
import ConfirmDialog from '../../components/ConfirmDialog.jsx';
import { useToast } from '../../components/Toast.jsx';
import { Alert, Badge, Button, EmptyState, Loading, PageHeader } from '../../components/ui.jsx';

const FILTERS = [
  { key: 'all', label: 'All' },
  { key: 'active', label: 'Active' },
  { key: 'inactive', label: 'Inactive' },
];

export default function RoutesPage() {
  const { user } = useAuth();
  const toast = useToast();
  const [routes, setRoutes] = useState(null);
  const [filter, setFilter] = useState('all');
  const [query, setQuery] = useState('');
  const [deactivating, setDeactivating] = useState(null);
  const [tokenSettingsMissing, setTokenSettingsMissing] = useState(false);

  const load = useCallback(async () => {
    try {
      setRoutes(await api('/api/tenant/routes'));
    } catch (err) {
      toast.error(err.message);
    }
  }, [toast]);

  // Without token settings the gateway rejects every request to these routes, so say so up front
  useEffect(() => {
    api('/api/tenant/token-settings')
      .then(() => setTokenSettingsMissing(false))
      .catch((err) => setTokenSettingsMissing(err instanceof ApiError && err.status === 404));
  }, []);

  useEffect(() => { load(); }, [load]);

  async function setActive(route, active) {
    try {
      if (active) {
        await api(`/api/tenant/routes/${route.routeId}/activate`, { method: 'PATCH' });
      } else {
        await api(`/api/tenant/routes/${route.routeId}`, { method: 'DELETE' });
      }
      toast.success(`${route.name} ${active ? 'activated' : 'deactivated'}`);
      load();
    } catch (err) {
      toast.error(err.message);
    }
  }

  const visible = (routes ?? [])
    .filter((route) => filter === 'all' || (filter === 'active') === route.active)
    .filter((route) => `${route.name} ${route.routeId} ${route.pathPattern} ${route.targetBaseUrl}`
      .toLowerCase().includes(query.trim().toLowerCase()));

  const newRouteButton = (
    <Link to="/tenant/routes/new" className="btn btn-primary"><Plus size={16} /><span>New route</span></Link>
  );

  return (
    <>
      <PageHeader
        title="Routes"
        subtitle={<>Public paths for <strong>{user.organizationName}</strong> are served under <code>/{user.organizationSlug}</code> on the gateway.</>}
        actions={newRouteButton}
      />

      {tokenSettingsMissing && (
        <Alert tone="info">
          <div className="warning-banner">
            <span>Token settings are not configured, so the gateway rejects every request to these routes with 401.</span>
            <Link className="link-action" to="/tenant/token-settings">Configure <ArrowRight size={14} /></Link>
          </div>
        </Alert>
      )}

      {routes === null ? <Loading /> : routes.length === 0 ? (
        <div className="card">
          <EmptyState
            icon={Route}
            title="No routes yet"
            text="A route maps a public path on the gateway to one of your upstream services."
            action={newRouteButton}
          />
        </div>
      ) : (
        <div className="card table-card">
          <div className="table-toolbar">
            <div className="segmented">
              {FILTERS.map(({ key, label }) => (
                <button key={key} className={filter === key ? 'active' : ''} onClick={() => setFilter(key)}>{label}</button>
              ))}
            </div>
            <div className="search">
              <Search size={16} />
              <input placeholder="Search routes" value={query} onChange={(event) => setQuery(event.target.value)} />
            </div>
          </div>
          <div className="table-scroll">
            <table className="table">
              <thead>
                <tr>
                  <th>Route</th>
                  <th>Mapping</th>
                  <th>Methods</th>
                  <th>Policies</th>
                  <th>Status</th>
                  <th aria-label="Actions" />
                </tr>
              </thead>
              <tbody>
                {visible.map((route) => (
                  <tr key={route.routeId} className={route.active ? '' : 'row-muted'}>
                    <td>
                      <div className="cell-title">{route.name}</div>
                      <span className="cell-sub">{route.routeId}</span>
                    </td>
                    <td>
                      <div className="mapping">
                        <code>/{user.organizationSlug}{route.pathPattern}</code>
                        <ArrowRight size={14} className="muted" />
                        <code className="mapping-target">{route.targetBaseUrl}{route.targetPathPrefix}</code>
                      </div>
                    </td>
                    <td>
                      <div className="chips">
                        {route.allowedMethods.map((method) => <span key={method} className={`method method-${method.toLowerCase()}`}>{method}</span>)}
                      </div>
                    </td>
                    <td>
                      <div className="policy-list">
                        <span>{route.requestsPerMinute ? `${route.requestsPerMinute}/min · ${route.perUserRequestsPerMinute}/min per user` : 'No rate limit'}</span>
                        <span>{route.responseTimeoutMs} ms timeout</span>
                        {route.idempotencyEnabled && <Badge tone="indigo">Idempotent</Badge>}
                      </div>
                    </td>
                    <td><Badge tone={route.active ? 'green' : 'neutral'}>{route.active ? 'Active' : 'Inactive'}</Badge></td>
                    <td className="cell-actions">
                      <Link to={`/tenant/routes/${route.routeId}/edit`} className="btn btn-ghost btn-sm"><Pencil size={16} /><span>Edit</span></Link>
                      {route.active ? (
                        <Button variant="ghost" size="sm" icon={Power} onClick={() => setDeactivating(route)}>Deactivate</Button>
                      ) : (
                        <Button variant="ghost" size="sm" icon={Power} onClick={() => setActive(route, true)}>Activate</Button>
                      )}
                    </td>
                  </tr>
                ))}
                {visible.length === 0 && (
                  <tr><td colSpan={6} className="muted table-empty">No routes match this filter.</td></tr>
                )}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {deactivating && (
        <ConfirmDialog
          title={`Deactivate ${deactivating.name}?`}
          message={`The gateway stops serving /${user.organizationSlug}${deactivating.pathPattern}. The configuration is kept and you can activate it again at any time.`}
          confirmLabel="Deactivate route"
          danger
          onConfirm={() => setActive(deactivating, false)}
          onClose={() => setDeactivating(null)}
        />
      )}
    </>
  );
}
