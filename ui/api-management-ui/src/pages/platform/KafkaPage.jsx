import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { ArrowLeft, Eye, RefreshCw, Workflow } from 'lucide-react';
import { api } from '../../api/client.js';
import Modal from '../../components/Modal.jsx';
import { useToast } from '../../components/Toast.jsx';
import { Alert, Badge, Button, Loading, PageHeader, formatDateTime } from '../../components/ui.jsx';

const formatNumber = (value) => (value ?? 0).toLocaleString();

function formatRetention(topic) {
  if (topic.cleanupPolicy?.includes('compact')) return 'Latest per key, forever';
  if (topic.retentionMs == null || topic.retentionMs < 0) return 'Forever';
  const hours = topic.retentionMs / 3_600_000;
  return hours >= 24 ? `${+(hours / 24).toFixed(1)} days` : `${+hours.toFixed(1)} h`;
}

/**
 * A read-only window into Kafka for platform admins: brokers, topics, consumer groups with their lag, and the
 * latest messages of a topic. Everything comes from api-management (/api/platform/kafka), which reads Kafka directly.
 */
export default function KafkaPage() {
  const toast = useToast();
  const [overview, setOverview] = useState(null);
  const [refreshing, setRefreshing] = useState(false);
  const [browsing, setBrowsing] = useState(null);

  const load = useCallback(async () => {
    setRefreshing(true);
    try {
      setOverview({ ...(await api('/api/platform/kafka')), checkedAt: new Date() });
    } catch (err) {
      toast.error(err.message);
    } finally {
      setRefreshing(false);
    }
  }, [toast]);

  useEffect(() => { load(); }, [load]);

  if (!overview) return <Loading />;
  const up = overview.status === 'UP';

  return (
    <>
      <PageHeader
        title="Kafka"
        subtitle={up
          ? `${overview.brokers.length} broker${overview.brokers.length === 1 ? '' : 's'}: ${overview.brokers.map((b) => `${b.host}:${b.port}`).join(', ')} · checked ${formatDateTime(overview.checkedAt)}`
          : 'Gateway configuration and traffic events flow through Kafka.'}
        actions={(
          <>
            <Link className="link-action" to="/platform"><ArrowLeft size={14} /> Overview</Link>
            <Button variant="secondary" icon={RefreshCw} loading={refreshing} onClick={load}>Refresh</Button>
          </>
        )}
      />

      {!up && <Alert>Kafka is not reachable. Start it with <code>docker compose up -d</code> in the Gatekeeper folder.</Alert>}

      {up && (
        <>
          <section className="card table-card">
            <div className="table-toolbar">
              <h2 className="toolbar-title"><Workflow size={18} /> Topics</h2>
              <span className="muted">{overview.topics.length} topics</span>
            </div>
            <div className="table-scroll">
              <table className="table">
                <thead>
                  <tr>
                    <th>Topic</th>
                    <th>Partitions</th>
                    <th>Messages</th>
                    <th>Kept</th>
                    <th aria-label="Actions" />
                  </tr>
                </thead>
                <tbody>
                  {overview.topics.length === 0 && (
                    <tr><td colSpan={5} className="table-empty muted">No topics yet. The services create theirs on startup.</td></tr>
                  )}
                  {overview.topics.map((topic) => (
                    <tr key={topic.name}>
                      <td><code>{topic.name}</code></td>
                      <td>{topic.partitions}</td>
                      <td>{formatNumber(topic.messages)}</td>
                      <td className="muted">{formatRetention(topic)}</td>
                      <td className="cell-actions">
                        <Button variant="ghost" size="sm" icon={Eye} onClick={() => setBrowsing(topic.name)}>Latest messages</Button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </section>

          <section className="card table-card gateway-sync">
            <div className="table-toolbar">
              <h2 className="toolbar-title">Consumer groups</h2>
              <span className="muted">Lag = messages not read yet</span>
            </div>
            <div className="table-scroll">
              <table className="table">
                <thead>
                  <tr>
                    <th>Group</th>
                    <th>State</th>
                    <th>Members</th>
                    <th>Topics</th>
                    <th>Lag</th>
                  </tr>
                </thead>
                <tbody>
                  {overview.consumerGroups.length === 0 && (
                    <tr><td colSpan={5} className="table-empty muted">No consumer groups yet.</td></tr>
                  )}
                  {overview.consumerGroups.map((group) => (
                    <tr key={group.groupId}>
                      <td><code>{group.groupId}</code></td>
                      <td><Badge tone={group.state === 'STABLE' ? 'green' : 'neutral'}>{group.state}</Badge></td>
                      <td>{group.members}</td>
                      <td className="muted">{group.topics.join(', ') || '—'}</td>
                      <td>
                        {group.lag == null
                          ? <span className="muted">—</span>
                          : <Badge tone={group.lag === 0 ? 'green' : 'amber'}>{formatNumber(group.lag)}</Badge>}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </section>
        </>
      )}

      {browsing && <MessagesModal topic={browsing} onClose={() => setBrowsing(null)} />}
    </>
  );
}

function MessagesModal({ topic, onClose }) {
  const [messages, setMessages] = useState(null);
  const [error, setError] = useState(null);

  useEffect(() => {
    api(`/api/platform/kafka/topics/${encodeURIComponent(topic)}/messages?limit=20`)
      .then(setMessages)
      .catch((err) => setError(err.message));
  }, [topic]);

  return (
    <Modal title={topic} description="The 20 newest messages, newest first" onClose={onClose} width={860}>
      {error && <Alert>{error}</Alert>}
      {!error && !messages && <Loading />}
      {messages?.length === 0 && <p className="muted">This topic has no messages yet.</p>}
      {messages?.length > 0 && (
        <ul className="message-list">
          {messages.map((message) => (
            <li key={`${message.partition}-${message.offset}`}>
              <div className="message-meta">
                <span>Partition {message.partition} · offset {message.offset}</span>
                <span>key <code>{message.key ?? 'none'}</code></span>
                <span className="muted">{formatDateTime(message.timestamp)}</span>
              </div>
              <pre className="message-value">{pretty(message.value)}{message.truncated && '\n… (cut)'}</pre>
            </li>
          ))}
        </ul>
      )}
    </Modal>
  );
}

function pretty(value) {
  if (value == null) return 'null (tombstone)';
  try {
    return JSON.stringify(JSON.parse(value), null, 2);
  } catch {
    return value;
  }
}
