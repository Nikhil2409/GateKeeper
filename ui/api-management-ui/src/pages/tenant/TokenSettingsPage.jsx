import { useEffect, useState } from 'react';
import { ShieldCheck } from 'lucide-react';
import { ApiError, api } from '../../api/client.js';
import { useAuth } from '../../auth/AuthContext.jsx';
import { useToast } from '../../components/Toast.jsx';
import { Alert, Button, Field, Loading, PageHeader } from '../../components/ui.jsx';

const EMPTY = { issuer: '', jwksUri: '', audience: '' };

export default function TokenSettingsPage() {
  const { user } = useAuth();
  const toast = useToast();
  const [form, setForm] = useState(null);
  const [configured, setConfigured] = useState(false);
  const [errors, setErrors] = useState({});
  const [error, setError] = useState(null);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    api('/api/tenant/token-settings')
      .then((settings) => {
        setForm(settings);
        setConfigured(true);
      })
      .catch((err) => {
        if (err instanceof ApiError && err.status === 404) setForm(EMPTY);
        else toast.error(err.message);
      });
  }, [toast]);

  if (!form) return <Loading />;

  const set = (field, value) => setForm((current) => ({ ...current, [field]: value }));

  async function handleSubmit(event) {
    event.preventDefault();
    setSaving(true);
    setErrors({});
    setError(null);
    try {
      setForm(await api('/api/tenant/token-settings', { method: 'PUT', body: form }));
      setConfigured(true);
      toast.success('Token settings saved and published to the gateway as a new configuration version.');
    } catch (err) {
      setErrors(err.fieldErrors);
      if (!Object.keys(err.fieldErrors).length) setError(err.message);
    } finally {
      setSaving(false);
    }
  }

  return (
    <>
      <PageHeader
        title="Token settings"
        subtitle="How the gateway checks that requests to your routes come from your signed-in users."
      />

      <div className="route-form">
        <form className="route-form-main" onSubmit={handleSubmit}>
          {!configured && (
            <Alert tone="info">
              Not configured yet. Until you save these settings, the gateway rejects every request to your routes with 401.
            </Alert>
          )}
          {error && <Alert>{error}</Alert>}

          <section className="card form-section">
            <h2>Your auth server</h2>
            <Field label="Issuer" error={errors.issuer} hint="Must match the iss claim in your users' tokens">
              <input value={form.issuer} onChange={(e) => set('issuer', e.target.value)} placeholder="https://auth.example.com/" required />
            </Field>
            <Field label="JWKS URL" error={errors.jwksUri} hint="Where your auth server publishes its public signing keys">
              <input value={form.jwksUri} onChange={(e) => set('jwksUri', e.target.value)} placeholder="https://auth.example.com/.well-known/jwks.json" required />
            </Field>
            <Field label="Audience" error={errors.audience} hint="Must match the aud claim; keeps tokens meant for other APIs out">
              <input value={form.audience} onChange={(e) => set('audience', e.target.value)} placeholder={`${user.organizationSlug}-api`} required />
            </Field>
          </section>

          <div className="form-actions">
            <Button type="submit" loading={saving}>{configured ? 'Save changes' : 'Save settings'}</Button>
          </div>
        </form>

        <aside className="route-form-side">
          <div className="card preview-card">
            <h3><ShieldCheck size={16} style={{ verticalAlign: '-3px', marginRight: 6 }} />How it works</h3>
            <ul className="explain-list">
              <li>Your users sign in with your own auth server and get a signed token.</li>
              <li>They call <code>/{user.organizationSlug}/…</code> with <code>Authorization: Bearer &lt;token&gt;</code>.</li>
              <li>The gateway checks the signature with your public keys, plus the issuer, audience and expiry.</li>
              <li>Valid: forwarded with <code>X-User-Id</code>. Invalid or missing: 401, your backend never sees it.</li>
              <li>Tokens of other organizations never verify against your keys.</li>
            </ul>
          </div>
        </aside>
      </div>
    </>
  );
}
