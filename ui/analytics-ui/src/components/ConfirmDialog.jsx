import { useState } from 'react';
import Modal from './Modal.jsx';
import { Button } from './ui.jsx';

export default function ConfirmDialog({ title, message, confirmLabel, danger = false, onConfirm, onClose }) {
  const [busy, setBusy] = useState(false);

  async function handleConfirm() {
    setBusy(true);
    try {
      await onConfirm();
      onClose();
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal title={title} onClose={onClose} width={440}>
      <div className="modal-body">
        <p className="confirm-message">{message}</p>
      </div>
      <div className="modal-footer">
        <Button variant="ghost" onClick={onClose} disabled={busy}>Cancel</Button>
        <Button variant={danger ? 'danger' : 'primary'} onClick={handleConfirm} loading={busy}>
          {confirmLabel}
        </Button>
      </div>
    </Modal>
  );
}
