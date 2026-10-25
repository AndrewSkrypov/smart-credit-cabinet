import { Button, Dialog, DialogActions, DialogContent, DialogTitle } from '@mui/material';
import { useCallback, useState } from 'react';
import { useBeforeUnload, useBlocker } from 'react-router-dom';

/** Both version editors use the same guard for navigation and local object changes. */
export function useUnsavedChanges(dirty: boolean, busy = false) {
  const blocker = useBlocker(({ currentLocation, nextLocation }) =>
    dirty && (currentLocation.pathname !== nextLocation.pathname || currentLocation.search !== nextLocation.search));
  const [pending, setPending] = useState<(() => void) | null>(null);
  useBeforeUnload(useCallback((event) => {
    if (dirty) { event.preventDefault(); event.returnValue = ''; }
  }, [dirty]));

  const request = (action: () => void) => {
    if (!dirty) action();
    else setPending(() => action);
  };
  const cancel = () => {
    if (busy) return;
    setPending(null);
    if (blocker.state === 'blocked') blocker.reset();
  };
  const discard = () => {
    if (busy) return;
    if (blocker.state === 'blocked') blocker.proceed();
    else { const action = pending; setPending(null); action?.(); }
  };
  const dialog = (
    <Dialog open={blocker.state === 'blocked' || pending !== null} onClose={cancel} fullWidth maxWidth="sm">
      <DialogTitle>Оставить несохранённые изменения?</DialogTitle>
      <DialogContent>Правки не сохранены. Можно вернуться к форме или продолжить без сохранения.</DialogContent>
      <DialogActions>
        <Button disabled={busy} onClick={cancel}>Вернуться к форме</Button>
        <Button disabled={busy} color="error" onClick={discard}>Продолжить без сохранения</Button>
      </DialogActions>
    </Dialog>
  );
  return { request, dialog };
}
