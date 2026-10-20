import { useQuery } from '../api/useQuery.js';
import { BarList, ChartCard, DataTable, compact, formatMs, formatPercent } from '../components/charts.jsx';
import { FilterBar, RANGES, useFilters } from '../components/Filters.jsx';
import { Loading, PageHeader } from '../components/ui.jsx';

export default function OrganizationsPage() {
  const { range } = useFilters();
  const { data, loading } = useQuery(`/analytics/organizations?range=${range}`);
  const rangeLabel = RANGES.find((r) => r.key === range).label.toLowerCase();

  const columns = [
    { label: 'Organization', render: (r) => r.organizationName },
    { label: 'Requests', numeric: true, render: (r) => compact(r.requests) },
    { label: 'Error rate', numeric: true, render: (r) => formatPercent(r.errorRate) },
    { label: '5xx rate', numeric: true, render: (r) => formatPercent(r.serverErrorRate, 2) },
    { label: 'p95', numeric: true, render: (r) => formatMs(r.p95) },
    { label: '429s', numeric: true, render: (r) => compact(r.rateLimited) },
  ];

  return (
    <>
      <PageHeader title="Organizations" subtitle="How traffic is spread across tenants. Visible to platform admins only." />
      <FilterBar showOrganization={false} />

      {!data ? <Loading /> : (
        <>
          <ChartCard title="Requests by organization" subtitle={rangeLabel} loading={loading} table={{ columns, rows: data }}>
            <BarList items={data.map((o) => ({ label: o.organizationName, sub: `/${o.organizationSlug}`, value: o.requests }))} />
          </ChartCard>
          <section className="card table-card" style={{ marginTop: 16, opacity: loading ? 0.5 : 1 }}>
            <DataTable columns={columns} rows={data} />
          </section>
        </>
      )}
    </>
  );
}
