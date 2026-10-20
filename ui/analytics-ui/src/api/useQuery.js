import { useEffect, useState } from 'react';
import { useToast } from '../components/Toast.jsx';
import { api } from './client.js';

// Fetches `path` whenever it changes and keeps the previous data while reloading,
// so charts dim instead of flashing empty (pass null to skip)
export function useQuery(path) {
  const toast = useToast();
  const [state, setState] = useState({ data: null, loading: Boolean(path) });

  useEffect(() => {
    if (!path) return undefined;
    let cancelled = false;
    setState((current) => ({ ...current, loading: true }));
    api(path)
      .then((data) => !cancelled && setState({ data, loading: false }))
      .catch((err) => {
        if (cancelled) return;
        toast.error(err.message);
        setState((current) => ({ ...current, loading: false }));
      });
    return () => { cancelled = true; };
  }, [path, toast]);

  return state;
}
