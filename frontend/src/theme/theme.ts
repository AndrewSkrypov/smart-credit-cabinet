import { alpha, createTheme } from '@mui/material/styles';

// Tokens of docs/design-tokens.md (the bank's public business pages). Components take colours and
// sizes from here, never as hex values in place, so the look changes in one file.
export const tokens = {
  red: '#ef3124',
  redDark: '#d22b1f',
  ink: '#030306',
  text: 'rgba(3, 3, 6, 0.88)',
  neutral: '#898991',
  dark: '#212124',
  secondaryFill: 'rgba(15, 25, 55, 0.1)',
  inputFill: 'rgba(11, 31, 53, 0.07)',
  line: '#d5d6dc',
  page: '#f3f4f5',
  success: '#0d7f4f',
  warning: '#b75c00',
  info: '#2a5bd7'
} as const;

// Phones and tablets are used with a finger: interactive elements there are at least 44×44 px.
const touch = '@media (max-width: 899.95px)';

const fontFamily = '"Inter", -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif';

export const cabinetTheme = createTheme({
  palette: {
    mode: 'light',
    primary: { main: tokens.red, dark: tokens.redDark, contrastText: '#ffffff' },
    secondary: { main: tokens.dark, contrastText: '#ffffff' },
    error: { main: '#c4211a' },
    success: { main: tokens.success },
    warning: { main: tokens.warning },
    info: { main: tokens.info },
    text: { primary: tokens.text, secondary: tokens.neutral },
    divider: tokens.line,
    background: { default: tokens.page, paper: '#ffffff' }
  },
  shape: { borderRadius: 10 },
  spacing: 8,
  typography: {
    fontFamily,
    // Sums and payments line up in columns.
    allVariants: { fontFeatureSettings: '"tnum"' },
    h4: { fontSize: '2.5rem', fontWeight: 700, letterSpacing: '-0.01em', lineHeight: 1.2 },
    h5: { fontSize: '1.875rem', fontWeight: 700, lineHeight: 1.25 },
    h6: { fontSize: '1.375rem', fontWeight: 700, lineHeight: 1.3 },
    subtitle1: { fontSize: '1.125rem', fontWeight: 500 },
    body1: { fontSize: '1rem' },
    body2: { fontSize: '0.875rem' },
    caption: { fontSize: '0.75rem' },
    button: { textTransform: 'none', fontWeight: 500, fontSize: '1rem' }
  },
  components: {
    MuiCssBaseline: {
      styleOverrides: { body: { color: tokens.text } }
    },
    MuiButton: {
      defaultProps: { disableElevation: true },
      styleOverrides: {
        root: {
          borderRadius: 10,
          minHeight: 48,
          paddingInline: 19,
          transition: 'background-color 0.2s ease, border-color 0.2s ease, color 0.2s ease',
          [touch]: { minHeight: 48 }
        },
        sizeLarge: { minHeight: 56, paddingInline: 23 },
        sizeSmall: { minHeight: 36, paddingInline: 12, fontSize: '0.875rem', [touch]: { minHeight: 44 } },
        // Secondary actions: the light filled button of the brand instead of an outline.
        outlined: {
          border: '1px solid transparent',
          backgroundColor: tokens.secondaryFill,
          color: tokens.ink,
          '&:hover': { border: '1px solid transparent', backgroundColor: alpha('#0f1937', 0.16) }
        },
        text: { color: tokens.ink }
      }
    },
    MuiIconButton: { styleOverrides: { root: { [touch]: { minWidth: 44, minHeight: 44 } } } },
    MuiTextField: { defaultProps: { variant: 'filled' } },
    MuiFilledInput: {
      defaultProps: { disableUnderline: true },
      styleOverrides: {
        root: {
          backgroundColor: tokens.inputFill,
          borderRadius: '4px 4px 0 0',
          borderBottom: '1px solid transparent',
          transition: 'box-shadow 0.3s ease-in-out, background-color 0.2s ease',
          '&:hover': { backgroundColor: alpha('#0b1f35', 0.1) },
          '&.Mui-focused': { backgroundColor: tokens.inputFill, boxShadow: `inset 0 -2px 0 ${tokens.ink}` },
          '&.Mui-error': { boxShadow: `inset 0 -2px 0 #c4211a` },
          [touch]: { minHeight: 56 }
        }
      }
    },
    MuiSelect: { defaultProps: { variant: 'filled' } },
    MuiPaper: {
      defaultProps: { elevation: 0 },
      styleOverrides: {
        rounded: { borderRadius: 24 },
        // White cards on the grey page carry no frame; `borderColor` in place marks a block that needs attention.
        outlined: { border: '1px solid transparent' }
      }
    },
    MuiCard: { styleOverrides: { root: { borderRadius: 16 } } },
    MuiCardActionArea: { styleOverrides: { focusHighlight: { backgroundColor: tokens.ink } } },
    MuiChip: {
      styleOverrides: {
        root: {
          borderRadius: 99,
          fontWeight: 500,
          fontSize: '0.875rem',
          '&.MuiChip-colorDefault': { backgroundColor: tokens.secondaryFill, color: tokens.ink }
        }
      }
    },
    MuiAlert: { styleOverrides: { root: { borderRadius: 16 } } },
    MuiDialog: { styleOverrides: { paper: { borderRadius: 24 } } },
    MuiAppBar: { styleOverrides: { root: { backgroundColor: '#ffffff', color: tokens.ink, borderBottom: `1px solid ${tokens.line}` } } },
    MuiLink: { defaultProps: { underline: 'hover' }, styleOverrides: { root: { color: tokens.ink, transition: 'opacity 0.15s ease-in-out' } } },
    MuiStepIcon: { styleOverrides: { root: { '&.Mui-completed': { color: tokens.dark }, '&.Mui-active': { color: tokens.red } } } },
    MuiTableCell: { styleOverrides: { head: { color: tokens.neutral, fontWeight: 500 } } }
  }
});
