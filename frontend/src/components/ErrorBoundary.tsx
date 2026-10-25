import { Box, Button, Stack, Typography } from '@mui/material';
import { Component, ErrorInfo, ReactNode } from 'react';
import { DocumentSurface } from './DocumentSurface';
import { useLocation } from 'react-router-dom';

const RELOADED_FOR_NEW_VERSION = 'cabinet.reloadedForNewVersion';

// After a release the old page asks for code chunks that no longer exist on the server.
function isStaleChunk(error: Error): boolean {
  return /Failed to fetch dynamically imported module|Importing a module script failed|Loading chunk/i.test(error.message);
}

interface State {
  error: Error | null;
}

/**
 * A rendering error shows a calm message instead of a blank screen. A missing code chunk
 * means a new version was released while the tab was open: the page reloads once.
 */
class Boundary extends Component<{ children: ReactNode }, State> {
  state: State = { error: null };

  static getDerivedStateFromError(error: Error): State {
    return { error };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    if (isStaleChunk(error)) {
      let alreadyReloaded = false;
      try {
        alreadyReloaded = sessionStorage.getItem(RELOADED_FOR_NEW_VERSION) === '1';
        sessionStorage.setItem(RELOADED_FOR_NEW_VERSION, '1');
      } catch {
        // storage unavailable: fall through to the message
      }
      if (!alreadyReloaded) window.location.reload();
      return;
    }
    console.error('Render error', error, info.componentStack);
  }

  render() {
    const { error } = this.state;
    if (!error) return this.props.children;
    const newVersion = isStaleChunk(error);
    return (
      <DocumentSurface kind="light" priority={1}><Box sx={{ display: 'grid', placeItems: 'center', minHeight: '100vh', bgcolor: 'background.default', p: 3 }}>
        <Stack spacing={2} alignItems="center" sx={{ maxWidth: 480, textAlign: 'center' }}>
          <Typography variant="h5">{newVersion ? 'Вышла новая версия кабинета' : 'Не удалось показать страницу'}</Typography>
          <Typography color="text.secondary">
            {newVersion
              ? 'Обновите страницу, чтобы продолжить работу.'
              : 'Произошла ошибка в интерфейсе. Обновите страницу; если ошибка повторится, напишите в поддержку.'}
          </Typography>
          <Button variant="contained" onClick={() => window.location.reload()}>Обновить страницу</Button>
        </Stack>
      </Box></DocumentSurface>
    );
  }
}

/** Resets on navigation: an error on one page does not block the others. */
export function RouteErrorBoundary({ children }: { children: ReactNode }) {
  const location = useLocation();
  return <Boundary key={location.pathname}>{children}</Boundary>;
}
