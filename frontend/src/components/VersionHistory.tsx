import { Box, Button, Chip, Dialog, DialogActions, DialogContent, DialogTitle, Paper, Stack, Typography } from '@mui/material';
import { ReactNode } from 'react';
import { roleLabels } from '../shared/labels';
import { Role } from '../types';

export interface RevisionMetadata {
  version: number;
  createdAt: string;
  authorId: string | null;
  authorName?: string | null;
  authorRole?: string | null;
  reason?: string | null;
}

const fullTime = new Intl.DateTimeFormat('ru-RU', {
  year: 'numeric', month: 'long', day: 'numeric', hour: '2-digit', minute: '2-digit', timeZone: 'Europe/Moscow'
});

export function VersionMetadata({ item }: { item: RevisionMetadata }) {
  const role = item.authorRole ? roleLabels[item.authorRole as Role] ?? item.authorRole : null;
  return (
    <Stack spacing={0.5} sx={{ overflowWrap: 'anywhere' }}>
      <Typography variant="body2" color="text.secondary">{fullTime.format(new Date(item.createdAt))} МСК</Typography>
      <Typography variant="body2">Автор: {item.authorName ?? item.authorId ?? 'Начальная системная версия'}{role ? ` · ${role}` : ''}</Typography>
      {item.authorName && item.authorId && <Typography variant="caption" color="text.secondary">ID: {item.authorId}</Typography>}
      {!item.authorName && item.authorId && <Typography variant="caption" color="text.secondary">Имя автора не сохранено в этой версии</Typography>}
      {item.reason && <Typography variant="body2">{item.reason}</Typography>}
    </Stack>
  );
}

export function VersionHistory({ title, items, currentVersion, onOpen }: {
  title: string; items: RevisionMetadata[]; currentVersion: number; onOpen: (version: number) => void;
}) {
  return (
    <Paper sx={{ p: { xs: 3, md: 4 } }}>
      <Typography variant="h6" component="h2" sx={{ mb: 2 }}>{title}</Typography>
      <Stack spacing={2}>
        {items.map((item) => (
          <Box key={item.version} sx={{ pb: 2, borderBottom: 1, borderColor: 'divider' }}>
            <Stack direction="row" alignItems="center" spacing={1} flexWrap="wrap" useFlexGap sx={{ mb: 1 }}>
              <Typography fontWeight={700}>Версия {item.version}</Typography>
              {item.version === currentVersion && <Chip label="Действует" size="small" color="success" />}
            </Stack>
            <VersionMetadata item={item} />
            <Button variant="outlined" sx={{ mt: 1 }} onClick={() => onOpen(item.version)} aria-label={`Открыть версию ${item.version}`}>Открыть</Button>
          </Box>
        ))}
      </Stack>
    </Paper>
  );
}

export function VersionPreviewDialog({ title, onClose, metadata, children }: {
  title: string; onClose: () => void; metadata?: RevisionMetadata; children: ReactNode;
}) {
  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="md">
      <DialogTitle>{title}</DialogTitle>
      <DialogContent>
        <Typography color="text.secondary" sx={{ mb: 2 }}>Сохранённые условия. Просмотр без редактирования.</Typography>
        {metadata && <Box sx={{ mb: 3 }}><VersionMetadata item={metadata} /></Box>}
        {children}
      </DialogContent>
      <DialogActions><Button onClick={onClose}>Закрыть</Button></DialogActions>
    </Dialog>
  );
}

export function SaveVersionDialog({ open, busy, onCancel, onConfirm }: {
  open: boolean; busy: boolean; onCancel: () => void; onConfirm: () => void;
}) {
  return (
    <Dialog open={open} onClose={() => !busy && onCancel()} maxWidth="sm" fullWidth>
      <DialogTitle>Сохранить новую версию?</DialogTitle>
      <DialogContent>Сохранённая история останется доступна. Новые операции будут использовать обновлённые параметры.</DialogContent>
      <DialogActions>
        <Button disabled={busy} onClick={onCancel}>Отмена</Button>
        <Button variant="contained" disabled={busy} onClick={onConfirm}>{busy ? 'Сохраняем…' : 'ОК'}</Button>
      </DialogActions>
    </Dialog>
  );
}
