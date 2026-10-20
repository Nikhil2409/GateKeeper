import { useEffect, useState } from 'react';
import { ChevronLeft, ChevronRight, Repeat, Search } from 'lucide-react';
import { useQuery } from '../api/useQuery.js';
import { FilterBar, useFilters } from '../components/Filters.jsx';
import { Badge, Button, Loading, PageHeader } from '../components/ui.jsx';

const STATUS_FILTERS = [
  { key: '', label: 'All' },
  { key: '2xx', label: '2xx' },
  { key: '4xx', label: '4xx' },
  { key: '5xx', label: '5xx' },
];
const PAGE_SIZE = 25;

function statusTone(status) {
  if (status >= 500) return 'red';
  if (status === 429) return 'amber';
  if (status >= 400) return 'neutral';
  return 'green';
}

export default function RequestLogPage() {
  const { query, range, org } = useFilters();
  const [status, setStatus] = useState('');
  const [search, setSearch] = useState('');
  const [debounced, setDebounced] = useState('');
  const [page, setPage] = useState(0);

  useEffect(() => {
    const timer = setTimeout(() => setDebounced(search), 300);
    return () => clearTimeout(timer);
  }, [search]);
  useEffect(() => setPage(0), [status, debounced, range, org]);

  const { data, loading } = useQuery(
    `/analytics/requests?${query(`&status=${status}&q=${encodeURIComponent(debounced)}&page=${page}&size=${PAGE_SIZE}`)}`);
  const pages = data ? Math.max(1, Math.ceil(data.total / PAGE_SIZE)) : 1;

  return (
    <>
      <PageHeader title="Request log" subtitle="Every request the gateway handled, newest first." />
      <FilterBar />

      <div className="card table-card">
        <div className="table-toolbar">
          <div className="segmented" role="group" aria-label="Status class">
            {STATUS_FILTERS.map(({ key, label }) => (
              <button key={label} className={status === key ? 'active' : ''} onClick={() => setStatus(key)}>{label}</button>
            ))}
          </div>
          <div className="search">
            <Search size={16} />
            <input placeholder="Search path, route, client IP, status or request / correlation id" value={search} onChange={(e) => setSearch(e.target.value)} />
          </div>
        </div>

        {!data ? <Loading /> : (
          <div style={{ opacity: loading ? 0.5 : 1 }}>
            <div className="table-scroll">
              <table className="table table-compact">
                <thead>
                  <tr><th>Time</th><th>Request</th><th>Status</th><th className="num">Latency</th><th>Client</th><th>Correlation id</th><th aria-label="Idempotent replay" /></tr>
                </thead>
                <tbody>
                  {data.items.map((row) => (
                    <tr key={row.id}>
                      <td className="muted" style={{ whiteSpace: 'nowrap' }}>
                        {new Date(row.timestamp).toLocaleString(undefined, { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit', second: '2-digit' })}
                      </td>
                      <td>
                        <span className={`method method-${row.method.toLowerCase()}`}>{row.method}</span>{' '}
                        <span className="mono">{row.path}</span>
                      </td>
                      <td><Badge tone={statusTone(row.status)}>{row.status}</Badge></td>
                      <td className="num">{row.latencyMs} ms</td>
                      <td className="mono muted">{row.clientIp}</td>
                      <td className="mono muted" title={row.correlationId}>{row.correlationId ? row.correlationId.slice(0, 12) : '—'}</td>
                      <td>{row.idempotentReplay && <span className="replay-flag" title="Idempotent replay: stored response returned"><Repeat size={13} /> Replay</span>}</td>
                    </tr>
                  ))}
                  {data.items.length === 0 && <tr><td colSpan={7} className="muted table-empty">No requests match these filters.</td></tr>}
                </tbody>
              </table>
            </div>
            <div className="pagination">
              <span className="muted">{data.total.toLocaleString()} requests · page {page + 1} of {pages}</span>
              <div>
                <Button variant="ghost" size="sm" icon={ChevronLeft} disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</Button>
                <Button variant="ghost" size="sm" icon={ChevronRight} disabled={page + 1 >= pages} onClick={() => setPage(page + 1)}>Next</Button>
              </div>
            </div>
          </div>
        )}
      </div>
    </>
  );
}
