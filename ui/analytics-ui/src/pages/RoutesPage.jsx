import { useState } from 'react';
import { ArrowDown } from 'lucide-react';
import { useQuery } from '../api/useQuery.js';
import { Sparkline, compact, formatMs, formatPercent } from '../components/charts.jsx';
import { FilterBar, useFilters } from '../components/Filters.jsx';
import { EmptyState, Loading, PageHeader } from '../components/ui.jsx';

const COLUMNS = [
  { key: 'requests', label: 'Requests', format: compact },
  { key: 'errorRate', label: 'Error rate', format: (v) => formatPercent(v) },
  { key: 'p50', label: 'p50', format: formatMs },
  { key: 'p95', label: 'p95', format: formatMs },
  { key: 'rateLimited', label: '429s', format: compact },
  { key: 'replays', label: 'Replays', format: compact },
];

export default function RoutesPage() {
  const { query } = useFilters();
  const { data, loading } = useQuery(`/analytics/routes?${query()}`);
  const [sortKey, setSortKey] = useState('requests');

  const rows = [...(data ?? [])].sort((a, b) => b[sortKey] - a[sortKey]);

  return (
    <>
      <PageHeader title="Routes" subtitle="Traffic, errors and latency per route. Click a column to sort." />
      <FilterBar />

      {!data ? <Loading /> : rows.length === 0 ? (
        <div className="card"><EmptyState title="No traffic in this range" text="Try a longer time range." /></div>
      ) : (
        <div className="card table-card" style={{ opacity: loading ? 0.5 : 1 }}>
          <div className="table-scroll">
            <table className="table">
              <thead>
                <tr>
                  <th>Route</th>
                  {COLUMNS.map((column) => (
                    <th key={column.key} className="num">
                      <button className={`sort-button ${sortKey === column.key ? 'active' : ''}`} onClick={() => setSortKey(column.key)}>
                        {column.label}{sortKey === column.key && <ArrowDown size={12} />}
                      </button>
                    </th>
                  ))}
                  <th>Requests trend</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((row) => (
                  <tr key={`${row.organizationSlug}/${row.routeId}`}>
                    <td>
                      <div className="cell-title">{row.routeId}</div>
                      <code className="cell-sub">/{row.organizationSlug}</code>
                    </td>
                    {COLUMNS.map((column) => <td key={column.key} className="num">{column.format(row[column.key])}</td>)}
                    <td style={{ width: 140 }}><Sparkline values={row.trend} /></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </>
  );
}
