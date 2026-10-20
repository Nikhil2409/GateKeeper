import { Navigate, useLocation } from 'react-router-dom';
import { homeFor, useAuth } from './AuthContext.jsx';

/**
 * Not signed in -> /login (and come back here afterwards).
 * Signed in with a role that is not allowed here -> that role's own home page.
 */
export default function RequireRole({ roles, children }) {
  const { user } = useAuth();
  const location = useLocation();

  if (!user) {
    return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />;
  }
  if (!roles.includes(user.role)) {
    return <Navigate to={homeFor(user)} replace />;
  }
  return children;
}
