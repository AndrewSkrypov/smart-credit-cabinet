import { RouteErrorBoundary } from './components/ErrorBoundary';
import { AppRoutes } from './routes/AppRoutes';

export default function App() {
  return (
    <RouteErrorBoundary>
      <AppRoutes />
    </RouteErrorBoundary>
  );
}
