import { useCallback, useEffect, useState } from 'react';
import { Eye, History, RefreshCw, RotateCcw } from 'lucide-react';
import { api } from '../../api/client.js';
import Modal from '../../components/Modal.jsx';
import { useToast } from '../../components/Toast.jsx';
import { Alert, Badge, Button, EmptyState, Loading, PageHeader, formatDateTime } from '../../components/ui.jsx';

const CHANGE_LABELS = {
  ORGANIZATION_CREATED: 'Organization created',
  ROUTE_CREATED: 'Route created',
  ROUTE_UPDATED: 'Route updated',
  ROUTE_DEACTIVATED: 'Route deactivated',
  ROUTE_ACTIVATED: 'Route activated',
  TOKEN_SETTINGS_SAVED: 'Token settings saved',
  ROLLED_BACK: 'Rolled back',
};

// The last kept versions of this organization's configuration, newest first, with a preview and one-step rollback
export default function ConfigurationHistoryPage() {
  const toast = useToast();
  const [versions, setVersions] = useState(null);
  const [refreshing, setRefreshing] = useState(false);
  const [previewing, setPreviewing] = useState(null);

  const load = useCallback(async () => {
    setRefreshing(true);
    try {
      setVersions(await api('/api/tenant/configuration/versions'));
    } catch (err) {
      toast.error(err.message);
    } finally {
      setRefreshing(false);
    }
  }, [toast]);

  useEffect(() => { load(); }, [load]);

  async function rollback(version) {
    const result = await api(`/api/tenant/configuration/versions/${version}/rollback`, { method: 'POST' });
    toast.success(`Version ${result.restoredVersion} restored as version ${result.newVersion}`);
    setPreviewing(null);
    await load();
  }

  if (!versions) return <Loading />;

  return (
    <>
      <PageHeader
        title="Configuration history"
        subtitle="The last 5 versions of your routes and token settings. Rolling back restores one of them as a new version."
        actions={<Button variant="secondary" icon={RefreshCw} loading={refreshing} onClick={load}>Refresh</Button>}
      />

      {versions.length === 0 ? (
        <EmptyState icon={History} title="No history yet" text="Every change to your routes or token settings is recorded here." />
      ) : (
        <div className="card table-card">
          <div className="table-scroll">
            <table className="table">
              <thead>
                <tr>
                  <th>Version</th>
                  <th>Change</th>
                  <th>By</th>
                  <th>When</th>
                  <th>Active routes</th>
                  <th>Token settings</th>
                  <th aria-label="Actions" />
                </tr>
              </thead>
              <tbody>
                {versions.map((version) => (
                  <tr key={version.version}>
                    <td>
                      <strong>v{version.version}</strong>{' '}
                      {version.current && <Badge tone="green">Current</Badge>}
                    </td>
                    <td>
                      <div className="cell-title">{CHANGE_LABELS[version.changeType] ?? version.changeType}</div>
                      {version.changeDetail && <code className="cell-sub">{version.changeDetail}</code>}
                    </td>
                    <td className="muted">{version.changedBy}</td>
                    <td className="muted">{formatDateTime(version.dateCreated)}</td>
                    <td>{version.activeRoutes}</td>
                    <td>
                      <Badge tone={version.tokenSettingsConfigured ? 'green' : 'amber'}>
                        {version.tokenSettingsConfigured ? 'Configured' : 'Missing'}
                      </Badge>
                    </td>
                    <td className="cell-actions">
                      <Button variant="ghost" size="sm" icon={Eye} onClick={() => setPreviewing(version.version)}>
                        Preview
                      </Button>
                      {!version.current && (
                        <Button variant="ghost" size="sm" icon={RotateCcw} onClick={() => setPreviewing(version.version)}>
                          Roll back
                        </Button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {previewing != null && (
        <VersionPreview version={previewing} onRollback={rollback} onClose={() => setPreviewing(null)} />
      )}
    </>
  );
}

// What rolling back to this version would change, and the version in full
function VersionPreview({ version, onRollback, onClose }) {
  const [preview, setPreview] = useState(null);
  const [error, setError] = useState(null);
  const [showFull, setShowFull] = useState(false);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    api(`/api/tenant/configuration/versions/${version}`).then(setPreview).catch((err) => setError(err.message));
  }, [version]);

  async function confirm() {
    setBusy(true);
    try {
      await onRollback(version);
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  }

  const changes = preview?.changes;
  const nothingChanges = changes && !changes.routesSwitchedOn.length && !changes.routesSwitchedOff.length
    && !changes.routesChanged.length && !changes.tokenSettings.length;

  return (
    <Modal
      title={preview?.current ? `Version ${version} (current)` : `Roll back to version ${version}?`}
      description={preview?.current ? 'This is the configuration in effect now.' : 'Compared with the configuration in effect now'}
      onClose={onClose}
      width={760}
    >
      <div className="modal-body preview-body">
        {error && <Alert>{error}</Alert>}
        {!preview && !error && <Loading />}

        {changes && !preview.current && (
          nothingChanges ? <p className="muted">Nothing would change: this version matches the current configuration.</p> : (
            <ul className="change-list">
              {changes.routesSwitchedOn.map((route) => (
                <li key={`on-${route.routeId}`}>
                  <Badge tone="green">Switched on</Badge>
                  <span><strong>{route.routeId}</strong> <code>{route.methods} {route.pathPattern}</code> → <code>{route.target}</code></span>
                </li>
              ))}
              {changes.routesSwitchedOff.map((route) => (
                <li key={`off-${route.routeId}`}>
                  <Badge tone="red">Switched off</Badge>
                  <span><strong>{route.routeId}</strong> <code>{route.methods} {route.pathPattern}</code> → <code>{route.target}</code></span>
                </li>
              ))}
              {changes.routesChanged.map((route) => (
                <li key={`changed-${route.routeId}`}>
                  <Badge tone="amber">Changed</Badge>
                  <div>
                    <strong>{route.routeId}</strong> <code>{route.pathPattern}</code>
                    <FieldChanges changes={route.changes} />
                  </div>
                </li>
              ))}
              {changes.tokenSettings.length > 0 && (
                <li>
                  <Badge tone="amber">Token settings</Badge>
                  <div><FieldChanges changes={changes.tokenSettings} /></div>
                </li>
              )}
              {changes.routesUnchanged > 0 && (
                <li>
                  <Badge tone="neutral">Unchanged</Badge>
                  <span className="muted">{changes.routesUnchanged} other active route{changes.routesUnchanged === 1 ? '' : 's'}</span>
                </li>
              )}
            </ul>
          )
        )}

        {preview && (
          <>
            <button type="button" className="link-action preview-toggle" onClick={() => setShowFull((value) => !value)}>
              {showFull ? 'Hide' : 'Show'} the full configuration of version {version}
            </button>
            {showFull && <FullConfiguration configuration={preview.configuration} />}
          </>
        )}
      </div>
      <div className="modal-footer">
        <Button variant="ghost" onClick={onClose} disabled={busy}>Close</Button>
        {preview && !preview.current && (
          <Button variant="danger" icon={RotateCcw} onClick={confirm} loading={busy}>
            Roll back to v{version}
          </Button>
        )}
      </div>
    </Modal>
  );
}

function FieldChanges({ changes }) {
  return (
    <ul className="field-changes">
      {changes.map((change) => (
        <li key={change.field}>
          <span className="muted">{change.field}:</span> <code>{change.before ?? '—'}</code> → <code>{change.after ?? '—'}</code>
        </li>
      ))}
    </ul>
  );
}

function FullConfiguration({ configuration }) {
  const settings = configuration.tokenSettings;
  return (
    <div className="full-config">
      <p>
        <strong>Token settings: </strong>
        {settings ? <><code>{settings.issuer}</code> · audience <code>{settings.audience}</code></> : <span className="muted">not configured</span>}
      </p>
      <div className="table-scroll">
        <table className="table table-compact">
          <thead>
            <tr><th>Route</th><th>Methods · path</th><th>Target</th><th>Limit</th><th>Allowed headers</th><th>State</th></tr>
          </thead>
          <tbody>
            {configuration.routes.map(({ route, active }) => (
              <tr key={route.routeId}>
                <td><strong>{route.routeId}</strong></td>
                <td><code>{[...route.allowedMethods].sort().join(', ')} {route.pathPattern}</code></td>
                <td><code>{route.targetBaseUrl}{route.targetPathPrefix}</code></td>
                <td>{route.requestsPerMinute ? `${route.requestsPerMinute}/min` : 'unlimited'}</td>
                <td className="muted">{route.allowedHeaders?.length ? [...route.allowedHeaders].sort().join(', ') : 'none'}</td>
                <td><Badge tone={active ? 'green' : 'neutral'}>{active ? 'Active' : 'Off'}</Badge></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
