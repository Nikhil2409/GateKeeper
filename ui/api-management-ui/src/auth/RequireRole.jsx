import { Navigate, useLocation } from 'react-router-dom';
import { homeFor, useAuth } from './AuthContext.jsx';

/**
 * Not signed in -> /login (and come back here afterwards).
 * Signed in with another role -> that role's own home page.
 */
export default function RequireRole({ role, children }) {
  const { user } = useAuth();
  const location = useLocation();

  if (!user) {
    return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />;
  }
  if (user.role !== role) {
    return <Navigate to={homeFor(user)} replace />;
  }
  return children;
}
