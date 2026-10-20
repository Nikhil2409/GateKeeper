import { getToken } from '../auth/session.js';
import { PageHeader } from '../components/ui.jsx';

const GRAFANA_URL = import.meta.env.VITE_GRAFANA_URL ?? 'http://localhost:3000';

// Grafana checks this same JWT against api-management's JWKS and lets in only PLATFORM_ADMIN (docker-compose.yml)
export default function SystemMetricsPage() {
  const src = `${GRAFANA_URL}/d/gatekeeper-gateway/gatekeeper?kiosk&refresh=10s&auth_token=${encodeURIComponent(getToken())}`;

  return (
    <>
      <PageHeader
        title="System metrics"
        subtitle="Live gateway, circuit breaker and JVM metrics from Prometheus, every 5 seconds. Visible to platform admins only."
      />
      <section className="card grafana-card">
        <iframe title="Gatekeeper metrics" src={src} className="grafana-frame" />
      </section>
    </>
  );
}
