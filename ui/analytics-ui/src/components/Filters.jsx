import { createContext, useContext, useMemo, useState } from 'react';
import { Building } from 'lucide-react';
import { useQuery } from '../api/useQuery.js';
import { useAuth } from '../auth/AuthContext.jsx';

export const RANGES = [
  { key: '1h', label: 'Last hour' },
  { key: '24h', label: 'Last 24 hours' },
  { key: '7d', label: 'Last 7 days' },
];

const FiltersContext = createContext(null);

// One set of filters for the whole app: every page and chart reads the same slice
export function FiltersProvider({ children }) {
  const { user } = useAuth();
  const isTenant = user.role === 'TENANT_ADMIN';
  const [range, setRange] = useState('24h');
  const [selectedOrg, setSelectedOrg] = useState('');
  // Tenant admins are always pinned to their own organization (the backend enforces this from the JWT too)
  const org = isTenant ? String(user.organizationId) : selectedOrg;

  const value = useMemo(() => ({
    range,
    setRange,
    org,
    setOrg: setSelectedOrg,
    isTenant,
    query: (extra = '') => `range=${range}${org ? `&org=${org}` : ''}${extra}`,
  }), [range, org, isTenant]);

  return <FiltersContext.Provider value={value}>{children}</FiltersContext.Provider>;
}

export function useFilters() {
  return useContext(FiltersContext);
}

export function FilterBar({ showOrganization = true, showRange = true }) {
  const { user } = useAuth();
  const { range, setRange, org, setOrg, isTenant } = useFilters();
  const { data: organizations } = useQuery(!isTenant && showOrganization ? `/analytics/organizations?range=${range}` : null);

  return (
    <div className="filter-row">
      {showRange && (
        <div className="segmented" role="group" aria-label="Time range">
          {RANGES.map(({ key, label }) => (
            <button key={key} className={range === key ? 'active' : ''} onClick={() => setRange(key)}>{label}</button>
          ))}
        </div>
      )}
      {showOrganization && (isTenant ? (
        <span className="badge badge-neutral"><Building size={13} /> {user.organizationName}</span>
      ) : (
        <select value={org} onChange={(event) => setOrg(event.target.value)} aria-label="Organization">
          <option value="">All organizations</option>
          {(organizations ?? []).map((o) => <option key={o.organizationId} value={o.organizationId}>{o.organizationName}</option>)}
        </select>
      ))}
    </div>
  );
}

// Axis and tooltip labels that suit each range
export function timeFormats(range) {
  const time = (iso) => new Date(iso).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' });
  const day = (iso) => new Date(iso).toLocaleDateString(undefined, { weekday: 'short', day: 'numeric' });
  const full = (iso) => new Date(iso).toLocaleString(undefined, { weekday: 'short', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' });
  return range === '7d' ? { axis: day, tooltip: full } : { axis: time, tooltip: full };
}
