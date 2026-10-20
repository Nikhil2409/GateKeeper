import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import {
  Activity, ArrowRight, Building, CircleCheck, Database, Network, RefreshCw, Server, TriangleAlert, Users, Workflow,
} from 'lucide-react';
import { api, gatewayApi, GATEWAY_URL } from '../../api/client.js';
import { useToast } from '../../components/Toast.jsx';
import { Alert, Badge, Button, Loading, PageHeader, formatDateTime } from '../../components/ui.jsx';

export default function PlatformOverviewPage() {
  const toast = useToast();
  const [data, setData] = useState(null);
  const [refreshing, setRefreshing] = useState(false);

  const load = useCallback(async () => {
    setRefreshing(true);
    try {
      const [organizations, admins, health, gatewayHealth, gatewayStatus, kafka] = await Promise.all([
        api('/api/platform/organizations'),
        api('/api/platform/tenant-admins'),
        api('/health', { token: null }).catch(() => null),
        gatewayApi('/_gateway/health'),
        gatewayApi('/_gateway/status'),
        api('/api/platform/kafka').catch(() => null),
      ]);
      setData({ organizations, admins, health, gatewayHealth, gatewayStatus, kafka, checkedAt: new Date() });
    } catch (err) {
      toast.error(err.message);
    } finally {
      setRefreshing(false);
    }
  }, [toast]);

  useEffect(() => { load(); }, [load]);

  if (!data) return <Loading />;

  const { organizations, admins, health, gatewayHealth, gatewayStatus, kafka } = data;
  const adminOrgIds = new Set(admins.map((admin) => admin.organizationId));
  const suspended = organizations.filter((org) => org.status === 'SUSPENDED');
  const withoutAdmin = organizations.filter((org) => !adminOrgIds.has(org.id));

  const stats = [
    { label: 'Organizations', value: organizations.length, icon: Building, tone: 'indigo' },
    { label: 'Active', value: organizations.length - suspended.length, icon: CircleCheck, tone: 'green' },
    { label: 'Suspended', value: suspended.length, icon: TriangleAlert, tone: 'amber' },
    { label: 'Tenant admins', value: admins.length, icon: Users, tone: 'sky' },
  ];

  return (
    <>
      <PageHeader
        title="Overview"
        subtitle="Organizations, their admins and the health of the platform."
        actions={<Button variant="secondary" icon={RefreshCw} loading={refreshing} onClick={load}>Refresh</Button>}
      />

      <div className="stat-grid">
        {stats.map(({ label, value, icon: Icon, tone }) => (
          <div key={label} className="card stat-card">
            <div className={`stat-icon tone-${tone}`}><Icon size={18} /></div>
            <div>
              <span className="stat-label">{label}</span>
              <strong className="stat-value">{value}</strong>
            </div>
          </div>
        ))}
      </div>

      <KafkaWidget kafka={kafka} />

      <div className="overview-grid">
        <section className="card">
          <div className="card-header">
            <h2><Activity size={18} /> Infrastructure health</h2>
            <span className="muted">Checked {formatDateTime(data.checkedAt)}</span>
          </div>
          <ul className="health-list">
            <HealthRow icon={Server} label="API management service" status={health?.serviceStatus} />
            <HealthRow icon={Database} label="MySQL database" status={health?.dbStatus} />
            <HealthRow icon={Activity} label="Kafka (gateway configuration)" status={health?.kafkaStatus} />
            <HealthRow icon={Network} label="Smart gateway" status={gatewayHealth?.data?.status} />
            {/* only the gateway uses Redis, so its health reports it; unknown when the gateway can't be reached */}
            <HealthRow icon={Database} label="Redis (rate limits, idempotency)" status={gatewayHealth?.data?.redis ?? 'UNKNOWN'} />
          </ul>
          <p className="muted small">
            {gatewayHealth?.data?.instanceId
              ? <>Gateway instance <code>{gatewayHealth.data.instanceId}</code>{gatewayHealth.data.reason && ` · ${gatewayHealth.data.reason}`}</>
              : <>The gateway is read directly from {GATEWAY_URL}; behind a load balancer this is whichever instance answers.</>}
          </p>
        </section>

        <section className="card">
          <div className="card-header">
            <h2><TriangleAlert size={18} /> Needs attention</h2>
          </div>
          {withoutAdmin.length === 0 && suspended.length === 0 ? (
            <p className="muted">Everything looks good. Every organization is active and has a tenant admin.</p>
          ) : (
            <ul className="attention-list">
              {withoutAdmin.map((org) => (
                <li key={`admin-${org.id}`}>
                  <div>
                    <strong>{org.name}</strong>
                    <span className="muted">No tenant admin assigned</span>
                  </div>
                  <Link className="link-action" to={`/platform/tenant-admins?org=${org.id}`}>
                    Assign <ArrowRight size={14} />
                  </Link>
                </li>
              ))}
              {suspended.map((org) => (
                <li key={`suspended-${org.id}`}>
                  <div>
                    <strong>{org.name}</strong>
                    <span className="muted">Suspended, routes are offline</span>
                  </div>
                  <Link className="link-action" to="/platform/organizations">
                    Review <ArrowRight size={14} />
                  </Link>
                </li>
              ))}
            </ul>
          )}
        </section>
      </div>

      <GatewaySync organizations={organizations} gatewayStatus={gatewayStatus} />
    </>
  );
}

/**
 * Has the gateway caught up with every change? api-management bumps an organization's configuration version on each
 * change; the gateway reports the version it has loaded. Equal = in sync, lower = the change is still on its way.
 */
function GatewaySync({ organizations, gatewayStatus }) {
  const status = gatewayStatus?.data;
  const loaded = new Map((status?.organizations ?? []).map((org) => [org.slug, org]));

  return (
    <section className="card table-card gateway-sync">
      <div className="table-toolbar">
        <h2 className="toolbar-title"><Network size={18} /> Gateway configuration</h2>
        {status && (
          <span className="muted">
            {status.organizations.length} organizations loaded
            {status.lastUpdateAt && ` · last update ${formatDateTime(status.lastUpdateAt)}`}
          </span>
        )}
      </div>

      {!gatewayStatus ? (
        <div className="table-message">
          <Alert>The gateway is not reachable at {GATEWAY_URL}. Is smart-gateway-service running?</Alert>
        </div>
      ) : (
        <div className="table-scroll">
          <table className="table">
            <thead>
              <tr>
                <th>Organization</th>
                <th>Version here</th>
                <th>Version on gateway</th>
                <th>Routes loaded</th>
                <th>Token settings</th>
                <th>Circuits</th>
                <th>Sync</th>
              </tr>
            </thead>
            <tbody>
              {organizations.length === 0 && (
                <tr><td colSpan={7} className="table-empty muted">No organizations yet.</td></tr>
              )}
              {organizations.map((org) => {
                const onGateway = loaded.get(org.slug);
                return (
                  <tr key={org.id}>
                    <td>
                      <div className="cell-title">{org.name}</div>
                      <code className="cell-sub">/{org.slug}</code>
                    </td>
                    <td>v{org.configurationVersion}</td>
                    <td>{onGateway ? `v${onGateway.version}` : <span className="muted">—</span>}</td>
                    <td>{onGateway ? onGateway.routes : <span className="muted">—</span>}</td>
                    <td>
                      {onGateway
                        ? <Badge tone={onGateway.tokenSettingsConfigured ? 'green' : 'amber'}>
                            {onGateway.tokenSettingsConfigured ? 'Configured' : 'Missing'}
                          </Badge>
                        : <span className="muted">—</span>}
                    </td>
                    <td>
                      {onGateway?.openCircuits?.length
                        ? <Badge tone="red">Open: {onGateway.openCircuits.join(', ')}</Badge>
                        : onGateway ? <Badge tone="green">All closed</Badge> : <span className="muted">—</span>}
                    </td>
                    <td><SyncBadge ready={status?.ready} expected={org.configurationVersion} onGateway={onGateway} /></td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

// A one-line Kafka summary; the whole card opens the Kafka page
function KafkaWidget({ kafka }) {
  const up = kafka?.status === 'UP';
  const messages = up ? kafka.topics.reduce((sum, topic) => sum + topic.messages, 0) : 0;
  const behind = up ? kafka.consumerGroups.reduce((sum, group) => sum + (group.lag ?? 0), 0) : 0;

  return (
    <Link to="/platform/kafka" className="card kafka-widget">
      <div className="stat-icon tone-indigo"><Workflow size={18} /></div>
      <div className="kafka-widget-body">
        <strong>Kafka</strong>
        <span className="muted small">
          {up
            ? `${kafka.topics.length} topics · ${messages.toLocaleString()} messages · ${kafka.consumerGroups.length} consumer groups`
              + (behind > 0 ? ` · ${behind.toLocaleString()} messages not read yet` : ' · all caught up')
            : 'Not reachable'}
        </span>
      </div>
      <Badge tone={up ? (behind > 0 ? 'amber' : 'green') : 'red'}>{up ? (behind > 0 ? 'Lagging' : 'UP') : 'DOWN'}</Badge>
      <span className="link-action">Open <ArrowRight size={14} /></span>
    </Link>
  );
}

function SyncBadge({ ready, expected, onGateway }) {
  if (!ready) return <Badge tone="neutral">Gateway not ready</Badge>;
  if (!onGateway) return <Badge tone="amber">Not loaded</Badge>;
  if (onGateway.version === expected) return <Badge tone="green">In sync</Badge>;
  if (onGateway.version < expected) return <Badge tone="amber">Behind by {expected - onGateway.version}</Badge>;
  return <Badge tone="red">Ahead (check versions)</Badge>;
}

function HealthRow({ icon: Icon, label, status }) {
  const tone = status === 'UP' ? 'green'
    : status === 'UNKNOWN' ? 'neutral'
      : status === 'DEGRADED' || status === 'NOT_READY' ? 'amber' : 'red';
  const text = status === 'UNKNOWN' ? 'Unknown'
    : status === 'NOT_READY' ? 'Not ready' : status ?? 'UNREACHABLE';
  return (
    <li>
      <Icon size={18} className="muted" />
      <span>{label}</span>
      <Badge tone={tone}>{text}</Badge>
    </li>
  );
}
