import React from 'react';
import ReactDOM from 'react-dom/client';
import '@fontsource/inter/400.css';
import '@fontsource/inter/500.css';
import '@fontsource/inter/600.css';
import '@fontsource/inter/700.css';
import '@fontsource/manrope/700.css';
import '@fontsource/manrope/800.css';
import { CssBaseline, ThemeProvider } from '@mui/material';
import { createBrowserRouter, RouterProvider } from 'react-router-dom';
import App from './App';
import { DocumentCanvas } from './components/DocumentSurface';
import { AuthProvider } from './auth/AuthProvider';
import { cabinetTheme } from './theme/theme';

// A data router provides navigation blocking, including browser Back, for version editors.
// The existing route tree and authentication remain in App.
const router = createBrowserRouter([{ path: '*', element: <AuthProvider><App /></AuthProvider> }]);

ReactDOM.createRoot(document.getElementById('root') as HTMLElement).render(
  <React.StrictMode>
    <ThemeProvider theme={cabinetTheme}>
      <CssBaseline />
      <DocumentCanvas><RouterProvider router={router} /></DocumentCanvas>
    </ThemeProvider>
  </React.StrictMode>
);
