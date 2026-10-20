import { useEffect, useState } from 'react';
import { useQuery } from '../api/useQuery.js';
import { ChartCard, DataTable, LineChart, compact, formatMs, formatPercent } from '../components/charts.jsx';
import { FilterBar, useFilters } from '../components/Filters.jsx';
import { EmptyState, Loading, PageHeader } from '../components/ui.jsx';

const REFRESH_MS = 10_000;
const rate = (value) => `${value < 10 ? value.toFixed(2) : compact(Math.round(value))}/s`;
const time = (iso) => new Date(iso).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit', second: '2-digit' });

// Prometheus-backed, refreshed every 10 s; tenant admins only ever get their own organization (analytic-service)
export default function LivePage() {
  const { org } = useFilters();
  const [tick, setTick] = useState(0);
  useEffect(() => {
    const timer = setInterval(() => setTick((t) => t + 1), REFRESH_MS);
    return () => clearInterval(timer);
  }, []);

  const scope = org ? `org=${org}&` : '';
  const routes = useQuery(`/analytics/live/routes?${scope}t=${tick}`);
  const timeseries = useQuery(`/analytics/live/timeseries?${scope}t=${tick}`);
  const series = timeseries.data ?? [];

  const columns = [
    { label: 'Route', render: (r) => <><div className="cell-title">{r.routeId}</div><code className="cell-sub">/{r.organizationSlug}</code></> },
    { label: 'Requests', numeric: true, render: (r) => rate(r.requestsPerSecond) },
    { label: '5xx rate', numeric: true, render: (r) => formatPercent(r.errorRate, 2) },
    { label: 'p50', numeric: true, render: (r) => formatMs(r.p50) },
    { label: 'p95', numeric: true, render: (r) => formatMs(r.p95) },
    { label: 'p99', numeric: true, render: (r) => formatMs(r.p99) },
    { label: '429s', numeric: true, render: (r) => rate(r.rateLimitedPerSecond) },
  ];

  return (
    <>
      <PageHeader title="Live performance" subtitle="Your routes right now: the last minute per route and the last 15 minutes overall, refreshed every 10 seconds." />
      <FilterBar showRange={false} />

      <div className="dash-grid">
        <ChartCard title="Requests per second" subtitle="Last 15 minutes" loading={!timeseries.data}>
          <LineChart data={series} xKey="timestamp" xFormat={time} yFormat={rate}
                     series={[{ key: 'requestsPerSecond', label: 'req/s', color: 'var(--series-1)' }]} />
        </ChartCard>
        <ChartCard title="p95 latency" subtitle="Last 15 minutes" loading={!timeseries.data}>
          <LineChart data={series} xKey="timestamp" xFormat={time} yFormat={formatMs}
                     series={[{ key: 'p95', label: 'p95', color: 'var(--series-2)' }]} />
        </ChartCard>
      </div>

      <section className="card table-card" style={{ marginTop: 16 }}>
        {!routes.data ? <Loading /> : routes.data.length === 0 ? (
          <EmptyState title="No traffic in the last few minutes" text="Send some requests through the gateway and they show up here within seconds." />
        ) : (
          <DataTable columns={columns} rows={routes.data} />
        )}
      </section>
    </>
  );
}
