import { Activity, CircleAlert, Gauge, Repeat, Timer } from 'lucide-react';
import { useQuery } from '../api/useQuery.js';
import { BarList, ChartCard, ColumnChart, LineChart, StatTile, compact, formatMs, formatPercent } from '../components/charts.jsx';
import { FilterBar, RANGES, timeFormats, useFilters } from '../components/Filters.jsx';
import { Loading, PageHeader } from '../components/ui.jsx';

export default function DashboardPage() {
  const { range, query, isTenant } = useFilters();
  const summary = useQuery(`/analytics/summary?${query()}`);
  const timeseries = useQuery(`/analytics/timeseries?${query()}`);
  const routes = useQuery(`/analytics/routes?${query()}`);
  const { axis, tooltip } = timeFormats(range);
  const rangeLabel = RANGES.find((r) => r.key === range).label.toLowerCase();

  const s = summary.data;
  const series = timeseries.data ?? [];
  const topRoutes = (routes.data ?? []).slice(0, 6);

  return (
    <>
      <PageHeader
        title="Traffic dashboard"
        subtitle={isTenant ? 'Traffic through the gateway for your organization.' : 'Traffic through the gateway across all organizations.'}
      />
      <FilterBar />

      {!s ? <Loading /> : (
        <div className="kpi-row" style={{ opacity: summary.loading ? 0.5 : 1 }}>
          <StatTile label="Requests" value={compact(s.requests)} delta={s.deltas.requests} upIsGood={null} trend={s.trends.requests} icon={Activity} />
          <StatTile label="Error rate (4xx + 5xx)" value={formatPercent(s.errorRate)} delta={s.deltas.errorRate} upIsGood={false} trend={s.trends.errorRate} icon={CircleAlert} />
          <StatTile label="p95 latency" value={formatMs(s.p95)} delta={s.deltas.p95} upIsGood={false} trend={s.trends.p95} icon={Timer} />
          <StatTile label="Rate limited (429)" value={compact(s.rateLimited)} delta={s.deltas.rateLimited} upIsGood={false} trend={s.trends.rateLimited} icon={Gauge} />
          <StatTile label="Idempotent replays" value={compact(s.replays)} delta={s.deltas.replays} upIsGood={null} trend={s.trends.replays} icon={Repeat} />
        </div>
      )}

      <div className="dash-grid">
        <ChartCard
          title="Requests"
          subtitle={`Per time bucket, ${rangeLabel}`}
          loading={timeseries.loading}
          table={{
            columns: [
              { label: 'Time', render: (r) => tooltip(r.t) },
              { label: 'Requests', numeric: true, render: (r) => compact(r.requests) },
              { label: '2xx', numeric: true, render: (r) => compact(r.s2xx) },
              { label: '4xx', numeric: true, render: (r) => compact(r.s4xx) },
              { label: '5xx', numeric: true, render: (r) => compact(r.s5xx) },
            ],
            rows: series,
          }}
        >
          <ColumnChart
            data={series}
            xKey="t"
            xFormat={axis}
            tooltipTitle={tooltip}
            yKey="requests"
            color="var(--series-1)"
            tooltipRows={(r) => [
              { label: 'requests', value: compact(r.requests), color: 'var(--series-1)' },
              { label: '2xx success', value: compact(r.s2xx) },
              { label: '4xx client errors (incl. 429)', value: compact(r.s4xx) },
              { label: '5xx server errors', value: compact(r.s5xx) },
            ]}
          />
        </ChartCard>

        <ChartCard
          title="Latency"
          subtitle="Median and 95th percentile response time"
          loading={timeseries.loading}
          table={{
            columns: [
              { label: 'Time', render: (r) => tooltip(r.t) },
              { label: 'p50', numeric: true, render: (r) => formatMs(r.p50) },
              { label: 'p95', numeric: true, render: (r) => formatMs(r.p95) },
            ],
            rows: series,
          }}
        >
          <LineChart
            data={series}
            xKey="t"
            xFormat={axis}
            tooltipTitle={tooltip}
            yFormat={formatMs}
            series={[
              { key: 'p50', label: 'p50', color: 'var(--series-1)' },
              { key: 'p95', label: 'p95', color: 'var(--series-2)' },
            ]}
          />
        </ChartCard>

        <ChartCard
          title="Error rate"
          subtitle="Share of requests answered with 4xx or 5xx"
          loading={timeseries.loading}
          table={{
            columns: [
              { label: 'Time', render: (r) => tooltip(r.t) },
              { label: 'Error rate', numeric: true, render: (r) => formatPercent(r.errorRate, 2) },
              { label: '5xx', numeric: true, render: (r) => compact(r.s5xx) },
              { label: '429', numeric: true, render: (r) => compact(r.rateLimited) },
            ],
            rows: series,
          }}
        >
          <LineChart
            data={series}
            xKey="t"
            xFormat={axis}
            tooltipTitle={tooltip}
            yFormat={(v) => formatPercent(v, 1)}
            series={[{ key: 'errorRate', label: 'error rate', color: 'var(--series-1)' }]}
          />
        </ChartCard>

        <ChartCard
          title="Busiest routes"
          subtitle={`By request count, ${rangeLabel}`}
          loading={routes.loading}
          table={{
            columns: [
              { label: 'Route', render: (r) => `/${r.organizationSlug} ${r.routeId}` },
              { label: 'Requests', numeric: true, render: (r) => compact(r.requests) },
              { label: 'Error rate', numeric: true, render: (r) => formatPercent(r.errorRate) },
            ],
            rows: topRoutes,
          }}
        >
          <BarList items={topRoutes.map((r) => ({ label: r.routeId, sub: `/${r.organizationSlug}`, value: r.requests }))} />
        </ChartCard>
      </div>
    </>
  );
}
