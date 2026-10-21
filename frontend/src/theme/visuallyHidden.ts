/** Hidden on screen, read by screen readers: text alternatives for icons and badges. */
export const visuallyHidden = {
  position: 'absolute',
  // Strings on purpose: in sx a bare 1 means 100% and -1 means one spacing step.
  width: '1px',
  height: '1px',
  padding: 0,
  margin: '-1px',
  overflow: 'hidden',
  clip: 'rect(0 0 0 0)',
  whiteSpace: 'nowrap',
  border: 0
} as const;
