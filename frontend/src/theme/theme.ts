import { createTheme } from '@mui/material/styles';

// Working theme for building the functionality. The visual design is redone at the last stage
// (see PLAN.md), so components take colours and sizes from here, never as hex values in place.

// Phones and tablets are used with a finger: interactive elements there are at least 44×44 px.
const touch = '@media (max-width: 899.95px)';

export const cabinetTheme = createTheme({
  palette: {
    mode: 'light',
    primary: { main: '#1f2a44' },
    secondary: { main: '#2f6fde' },
    background: { default: '#f5f6f8', paper: '#ffffff' }
  },
  shape: { borderRadius: 8 },
  typography: {
    fontFamily: '"Inter", -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif',
    button: { textTransform: 'none', fontWeight: 600 }
  },
  components: {
    MuiButton: { styleOverrides: { root: { [touch]: { minHeight: 44 } } } },
    MuiIconButton: { styleOverrides: { root: { [touch]: { minWidth: 44, minHeight: 44 } } } },
    MuiInputBase: { styleOverrides: { root: { [touch]: { minHeight: 44 } } } }
  }
});
