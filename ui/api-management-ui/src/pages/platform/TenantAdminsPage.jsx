import { useCallback, useEffect, useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { KeyRound, Plus, Trash, Users } from 'lucide-react';
import { api } from '../../api/client.js';
import ConfirmDialog from '../../components/ConfirmDialog.jsx';
import Modal from '../../components/Modal.jsx';
import { useToast } from '../../components/Toast.jsx';
import { Alert, Button, EmptyState, Field, Loading, PageHeader, formatDate } from '../../components/ui.jsx';

export default function TenantAdminsPage() {
  const toast = useToast();
  const [searchParams, setSearchParams] = useSearchParams();
  const [admins, setAdmins] = useState(null);
  const [organizations, setOrganizations] = useState([]);
  const [resetting, setResetting] = useState(null);
  const [deleting, setDeleting] = useState(null);

  // /platform/tenant-admins?org=5 opens the create dialog with that organization preselected
  const createForOrg = searchParams.get('org');
  const creating = createForOrg !== null;
  const openCreate = (orgId = '') => setSearchParams({ org: orgId });
  const closeCreate = () => setSearchParams({});

  const load = useCallback(async () => {
    try {
      const [tenantAdmins, orgs] = await Promise.all([
        api('/api/platform/tenant-admins'),
        api('/api/platform/organizations'),
      ]);
      setAdmins(tenantAdmins);
      setOrganizations(orgs);
    } catch (err) {
      toast.error(err.message);
    }
  }, [toast]);

  useEffect(() => { load(); }, [load]);

  const orgsWithoutAdmin = useMemo(() => {
    const taken = new Set((admins ?? []).map((admin) => admin.organizationId));
    return organizations.filter((org) => !taken.has(org.id));
  }, [admins, organizations]);

  async function deleteAdmin(admin) {
    try {
      await api(`/api/platform/tenant-admins/${admin.id}`, { method: 'DELETE' });
      toast.success(`${admin.username} deleted`);
      load();
    } catch (err) {
      toast.error(err.message);
    }
  }

  return (
    <>
      <PageHeader
        title="Tenant admins"
        subtitle="One admin per organization. They manage that organization's gateway routes."
        actions={<Button icon={Plus} onClick={() => openCreate()}>New tenant admin</Button>}
      />

      {admins === null ? <Loading /> : admins.length === 0 ? (
        <div className="card">
          <EmptyState
            icon={Users}
            title="No tenant admins yet"
            text="Create an organization first, then give it an admin who can configure its routes."
            action={<Button icon={Plus} onClick={() => openCreate()}>New tenant admin</Button>}
          />
        </div>
      ) : (
        <div className="card table-card">
          <div className="table-scroll">
            <table className="table">
              <thead>
                <tr>
                  <th>Username</th>
                  <th>Organization</th>
                  <th>Created</th>
                  <th>Last changed</th>
                  <th aria-label="Actions" />
                </tr>
              </thead>
              <tbody>
                {admins.map((admin) => (
                  <tr key={admin.id}>
                    <td>
                      <div className="user-cell">
                        <div className="avatar avatar-sm">{admin.username.slice(0, 2).toUpperCase()}</div>
                        <span className="cell-title">{admin.username}</span>
                      </div>
                    </td>
                    <td>{admin.organizationName}</td>
                    <td className="muted">{formatDate(admin.dateCreated)}</td>
                    <td className="muted">{formatDate(admin.dateUpdated)}</td>
                    <td className="cell-actions">
                      <Button variant="ghost" size="sm" icon={KeyRound} onClick={() => setResetting(admin)}>Reset password</Button>
                      <Button variant="ghost-danger" size="sm" icon={Trash} onClick={() => setDeleting(admin)} aria-label="Delete" />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {creating && admins !== null && (
        <CreateAdminModal
          organizations={orgsWithoutAdmin}
          initialOrgId={createForOrg}
          onClose={closeCreate}
          onCreated={(admin) => {
            closeCreate();
            toast.success(`${admin.username} can now sign in for ${admin.organizationName}`);
            load();
          }}
        />
      )}

      {resetting && (
        <ResetPasswordModal
          admin={resetting}
          onClose={() => setResetting(null)}
          onDone={() => {
            toast.success(`Password updated for ${resetting.username}`);
            setResetting(null);
          }}
        />
      )}

      {deleting && (
        <ConfirmDialog
          title={`Delete ${deleting.username}?`}
          message={`They are signed out immediately and can no longer sign in. You can then assign a new admin to ${deleting.organizationName}.`}
          confirmLabel="Delete admin"
          danger
          onConfirm={() => deleteAdmin(deleting)}
          onClose={() => setDeleting(null)}
        />
      )}
    </>
  );
}

function CreateAdminModal({ organizations, initialOrgId, onClose, onCreated }) {
  const validInitial = organizations.some((org) => String(org.id) === initialOrgId) ? initialOrgId : '';
  const [form, setForm] = useState({ username: '', password: '', organizationId: validInitial });
  const [errors, setErrors] = useState({});
  const [error, setError] = useState(null);
  const [saving, setSaving] = useState(false);

  async function handleSubmit(event) {
    event.preventDefault();
    setSaving(true);
    setErrors({});
    setError(null);
    try {
      onCreated(await api('/api/platform/tenant-admins', {
        method: 'POST',
        body: { ...form, organizationId: Number(form.organizationId) },
      }));
    } catch (err) {
      setErrors(err.fieldErrors);
      if (!Object.keys(err.fieldErrors).length) setError(err.message);
      setSaving(false);
    }
  }

  return (
    <Modal title="New tenant admin" description="Share these credentials with the organization's admin. There is no self sign-up." onClose={onClose}>
      <form onSubmit={handleSubmit}>
        <div className="modal-body">
          {error && <Alert>{error}</Alert>}
          {organizations.length === 0 ? (
            <Alert tone="info">Every organization already has a tenant admin. Create a new organization first.</Alert>
          ) : (
            <>
              <Field label="Organization" error={errors.organizationId}>
                <select
                  value={form.organizationId}
                  onChange={(event) => setForm({ ...form, organizationId: event.target.value })}
                  required
                >
                  <option value="" disabled>Select an organization</option>
                  {organizations.map((org) => <option key={org.id} value={org.id}>{org.name}</option>)}
                </select>
              </Field>
              <Field label="Username" error={errors.username} hint="3-50 letters, digits, dots, underscores or hyphens">
                <input
                  autoFocus
                  autoComplete="off"
                  value={form.username}
                  onChange={(event) => setForm({ ...form, username: event.target.value })}
                  required
                />
              </Field>
              <Field label="Temporary password" error={errors.password} hint="At least 8 characters">
                <input
                  type="password"
                  autoComplete="new-password"
                  value={form.password}
                  onChange={(event) => setForm({ ...form, password: event.target.value })}
                  required
                />
              </Field>
            </>
          )}
        </div>
        <div className="modal-footer">
          <Button type="button" variant="ghost" onClick={onClose}>Cancel</Button>
          <Button type="submit" loading={saving} disabled={organizations.length === 0}>Create admin</Button>
        </div>
      </form>
    </Modal>
  );
}

function ResetPasswordModal({ admin, onClose, onDone }) {
  const [password, setPassword] = useState('');
  const [error, setError] = useState(null);
  const [saving, setSaving] = useState(false);

  async function handleSubmit(event) {
    event.preventDefault();
    setSaving(true);
    setError(null);
    try {
      await api(`/api/platform/tenant-admins/${admin.id}/password`, { method: 'PATCH', body: { password } });
      onDone();
    } catch (err) {
      setError(err.fieldErrors.password ?? err.message);
      setSaving(false);
    }
  }

  return (
    <Modal title={`Reset password for ${admin.username}`} onClose={onClose}>
      <form onSubmit={handleSubmit}>
        <div className="modal-body">
          <Field label="New password" error={error} hint="At least 8 characters">
            <input
              autoFocus
              type="password"
              autoComplete="new-password"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              required
            />
          </Field>
        </div>
        <div className="modal-footer">
          <Button type="button" variant="ghost" onClick={onClose}>Cancel</Button>
          <Button type="submit" loading={saving}>Update password</Button>
        </div>
      </form>
    </Modal>
  );
}
