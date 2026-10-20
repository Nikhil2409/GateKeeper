import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { Building, Plus, Power, Search, UserPlus } from 'lucide-react';
import { api } from '../../api/client.js';
import ConfirmDialog from '../../components/ConfirmDialog.jsx';
import Modal from '../../components/Modal.jsx';
import { useToast } from '../../components/Toast.jsx';
import { Alert, Badge, Button, EmptyState, Field, Loading, PageHeader, formatDate } from '../../components/ui.jsx';

function slugify(value) {
  return value.toLowerCase().trim().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 100);
}

export default function OrganizationsPage() {
  const toast = useToast();
  const [organizations, setOrganizations] = useState(null);
  const [admins, setAdmins] = useState([]);
  const [query, setQuery] = useState('');
  const [creating, setCreating] = useState(false);
  const [suspending, setSuspending] = useState(null);

  const load = useCallback(async () => {
    try {
      const [orgs, tenantAdmins] = await Promise.all([
        api('/api/platform/organizations'),
        api('/api/platform/tenant-admins'),
      ]);
      setOrganizations(orgs);
      setAdmins(tenantAdmins);
    } catch (err) {
      toast.error(err.message);
    }
  }, [toast]);

  useEffect(() => { load(); }, [load]);

  const adminByOrg = useMemo(
    () => Object.fromEntries(admins.map((admin) => [admin.organizationId, admin])), [admins]);

  const visible = (organizations ?? []).filter((org) =>
    `${org.name} ${org.slug}`.toLowerCase().includes(query.trim().toLowerCase()));

  async function changeStatus(org, action) {
    try {
      await api(`/api/platform/organizations/${org.id}/${action}`, { method: 'PATCH' });
      toast.success(`${org.name} ${action === 'suspend' ? 'suspended' : 'activated'}`);
      load();
    } catch (err) {
      toast.error(err.message);
    }
  }

  return (
    <>
      <PageHeader
        title="Organizations"
        subtitle="Each organization gets one tenant admin and its own set of gateway routes."
        actions={<Button icon={Plus} onClick={() => setCreating(true)}>New organization</Button>}
      />

      {organizations === null ? <Loading /> : organizations.length === 0 ? (
        <div className="card">
          <EmptyState
            icon={Building}
            title="No organizations yet"
            text="Create the first organization, then assign it a tenant admin."
            action={<Button icon={Plus} onClick={() => setCreating(true)}>New organization</Button>}
          />
        </div>
      ) : (
        <div className="card table-card">
          <div className="table-toolbar">
            <div className="search">
              <Search size={16} />
              <input placeholder="Search by name or slug" value={query} onChange={(event) => setQuery(event.target.value)} />
            </div>
            <span className="muted">{visible.length} of {organizations.length}</span>
          </div>
          <div className="table-scroll">
            <table className="table">
              <thead>
                <tr>
                  <th>Organization</th>
                  <th>Status</th>
                  <th>Tenant admin</th>
                  <th>Created</th>
                  <th aria-label="Actions" />
                </tr>
              </thead>
              <tbody>
                {visible.map((org) => {
                  const admin = adminByOrg[org.id];
                  const active = org.status === 'ACTIVE';
                  return (
                    <tr key={org.id}>
                      <td>
                        <div className="cell-title">{org.name}</div>
                        <code className="cell-sub">/{org.slug}</code>
                      </td>
                      <td><Badge tone={active ? 'green' : 'amber'}>{active ? 'Active' : 'Suspended'}</Badge></td>
                      <td>
                        {admin ? admin.username : (
                          <Link className="link-action" to={`/platform/tenant-admins?org=${org.id}`}>
                            <UserPlus size={14} /> Assign admin
                          </Link>
                        )}
                      </td>
                      <td className="muted">{formatDate(org.dateCreated)}</td>
                      <td className="cell-actions">
                        {active ? (
                          <Button variant="ghost" size="sm" icon={Power} onClick={() => setSuspending(org)}>Suspend</Button>
                        ) : (
                          <Button variant="ghost" size="sm" icon={Power} onClick={() => changeStatus(org, 'activate')}>Activate</Button>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {creating && (
        <CreateOrganizationModal
          onClose={() => setCreating(false)}
          onCreated={(org) => {
            setCreating(false);
            toast.success(`${org.name} created`);
            load();
          }}
        />
      )}

      {suspending && (
        <ConfirmDialog
          title={`Suspend ${suspending.name}?`}
          message="Its tenant admin is signed out immediately and all of its routes are removed from the gateway until you activate it again."
          confirmLabel="Suspend organization"
          danger
          onConfirm={() => changeStatus(suspending, 'suspend')}
          onClose={() => setSuspending(null)}
        />
      )}
    </>
  );
}

function CreateOrganizationModal({ onClose, onCreated }) {
  const [name, setName] = useState('');
  const [slug, setSlug] = useState('');
  const [slugEdited, setSlugEdited] = useState(false);
  const [errors, setErrors] = useState({});
  const [error, setError] = useState(null);
  const [saving, setSaving] = useState(false);

  async function handleSubmit(event) {
    event.preventDefault();
    setSaving(true);
    setErrors({});
    setError(null);
    try {
      onCreated(await api('/api/platform/organizations', { method: 'POST', body: { name, slug } }));
    } catch (err) {
      setErrors(err.fieldErrors);
      if (!Object.keys(err.fieldErrors).length) setError(err.message);
      setSaving(false);
    }
  }

  return (
    <Modal title="New organization" description="The slug becomes the organization's public path on the gateway and cannot be changed later." onClose={onClose}>
      <form onSubmit={handleSubmit}>
        <div className="modal-body">
          {error && <Alert>{error}</Alert>}
          <Field label="Name" error={errors.name}>
            <input
              autoFocus
              value={name}
              onChange={(event) => {
                setName(event.target.value);
                if (!slugEdited) setSlug(slugify(event.target.value));
              }}
              placeholder="Acme Corporation"
              required
            />
          </Field>
          <Field label="Slug" error={errors.slug} hint={slug ? `Routes will be served under /${slug}/…` : 'Lowercase letters, digits and hyphens'}>
            <input
              value={slug}
              onChange={(event) => {
                setSlugEdited(true);
                setSlug(event.target.value);
              }}
              placeholder="acme"
              required
            />
          </Field>
        </div>
        <div className="modal-footer">
          <Button type="button" variant="ghost" onClick={onClose}>Cancel</Button>
          <Button type="submit" loading={saving}>Create organization</Button>
        </div>
      </form>
    </Modal>
  );
}
