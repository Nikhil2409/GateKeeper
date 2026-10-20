import { CircleX, Gauge, KeyRound, Repeat, TriangleAlert } from 'lucide-react';
import { useQuery } from '../api/useQuery.js';
import { Meter, StatTile, compact } from '../components/charts.jsx';
import { FilterBar, useFilters } from '../components/Filters.jsx';
import { Badge, EmptyState, Loading, PageHeader } from '../components/ui.jsx';

const IDEMPOTENCY_OUTCOMES = {
  REPLAYED: { tone: 'green', label: 'Replayed', meaning: 'Stored response returned, upstream not called again' },
  IN_PROGRESS_CONFLICT: { tone: 'amber', label: 'In progress', meaning: 'Retry arrived while the first request was running (409)' },
  BODY_MISMATCH: { tone: 'red', label: 'Body mismatch', meaning: 'Same key reused for a different request (422)' },
};

function minutesAgo(iso) {
  const minutes = Math.max(0, Math.round((Date.now() - new Date(iso).getTime()) / 60_000));
  return minutes === 0 ? 'just now' : `${minutes} min ago`;
}

export default function TrafficPoliciesPage() {
  const { org } = useFilters();
  const orgQuery = org ? `?org=${org}` : '';
  const limits = useQuery(`/analytics/rate-limits${orgQuery}`);
  const idempotency = useQuery(`/analytics/idempotency${orgQuery}`);

  const rows = [...(limits.data ?? [])].sort((a, b) => b.usedThisWindow / b.limitPerMinute - a.usedThisWindow / a.limitPerMinute);
  const idem = idempotency.data;

  return (
    <>
      <PageHeader
        title="Traffic policies"
        subtitle="How rate limits and idempotency are behaving, from the gateway's traffic events."
      />
      <FilterBar showRange={false} />

      <section className="card table-card" style={{ opacity: limits.loading ? 0.5 : 1 }}>
        <div className="table-toolbar">
          <strong><Gauge size={16} style={{ verticalAlign: '-3px', marginRight: 6 }} />Rate limits, current minute</strong>
          <span className="muted">Routes without a limit are not listed</span>
        </div>
        {!limits.data ? <Loading /> : rows.length === 0 ? (
          <EmptyState title="No rate-limited routes" text="None of the routes in scope have a rate limit configured." />
        ) : (
          <div className="table-scroll">
            <table className="table">
              <thead>
                <tr><th>Route</th><th className="num">Limit</th><th>Used this minute</th><th className="num">Rejected (429), last hour</th></tr>
              </thead>
              <tbody>
                {rows.map((row) => (
                  <tr key={`${row.organizationSlug}/${row.routeId}`}>
                    <td>
                      <div className="cell-title">{row.routeId}</div>
                      <code className="cell-sub">/{row.organizationSlug}</code>
                    </td>
                    <td className="num">{compact(row.limitPerMinute)}/min</td>
                    <td><Meter value={row.usedThisWindow} max={row.limitPerMinute} /></td>
                    <td className="num">{compact(row.rejectedLastHour)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      <h2 className="section-title">Idempotency, last hour</h2>
      {!idem ? <Loading /> : (
        <div style={{ opacity: idempotency.loading ? 0.5 : 1 }}>
          <div className="kpi-row">
            <StatTile label="Stored keys" value={compact(idem.storedKeys)} icon={KeyRound} />
            <StatTile label="Replays" value={compact(idem.replaysLastHour)} icon={Repeat} />
            <StatTile label="In-progress conflicts" value={compact(idem.inProgressConflictsLastHour)} icon={TriangleAlert} />
            <StatTile label="Body mismatches" value={compact(idem.bodyMismatchesLastHour)} icon={CircleX} />
          </div>

          <section className="card table-card">
            <div className="table-toolbar">
              <strong>Recent outcomes</strong>
              <span className="muted">Keys expire after {Math.round(idem.ttlSeconds / 3600)} h</span>
            </div>
            <div className="table-scroll">
              <table className="table table-compact">
                <thead><tr><th>When</th><th>Route</th><th>Key</th><th>Outcome</th></tr></thead>
                <tbody>
                  {idem.recent.map((row) => {
                    const info = IDEMPOTENCY_OUTCOMES[row.outcome];
                    return (
                      <tr key={row.key + row.at}>
                        <td className="muted">{minutesAgo(row.at)}</td>
                        <td><code>/{row.organizationSlug}</code> {row.routeId}</td>
                        <td className="mono">{row.key}</td>
                        <td>
                          <span className="outcome">
                            <Badge tone={info.tone}>{info.label}</Badge>
                            <span className="muted small">{info.meaning}</span>
                          </span>
                        </td>
                      </tr>
                    );
                  })}
                  {idem.recent.length === 0 && <tr><td colSpan={4} className="muted table-empty">No idempotent requests in the last hour.</td></tr>}
                </tbody>
              </table>
            </div>
          </section>
        </div>
      )}
    </>
  );
}
