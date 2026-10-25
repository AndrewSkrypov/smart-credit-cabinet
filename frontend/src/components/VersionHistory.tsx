import { Box, Button, Chip, Dialog, DialogActions, DialogContent, DialogTitle, Paper, Stack, Typography, useMediaQuery, useTheme } from '@mui/material';
import VisibilityOutlinedIcon from '@mui/icons-material/VisibilityOutlined';
import { ReactNode, useEffect, useId, useLayoutEffect, useMemo, useRef, useState } from 'react';
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
const author = (item: RevisionMetadata) => item.authorName || item.authorId || 'Начальная системная версия';

export function VersionMetadata({ item }: { item: RevisionMetadata }) {
  const role = item.authorRole ? roleLabels[item.authorRole as Role] ?? item.authorRole : null;
  return <Stack spacing={1} sx={{ overflowWrap: 'anywhere' }}>
    <Typography variant="body2" color="text.secondary">{fullTime.format(new Date(item.createdAt))} МСК</Typography>
    <Typography variant="body2">Автор: {author(item)}{role ? ` · ${role}` : ''}</Typography>
    {!item.authorName && item.authorId && <Typography variant="caption" color="text.secondary">Имя автора не сохранено в этой версии</Typography>}
    {item.reason && <Typography variant="body2" sx={{ whiteSpace: 'pre-wrap' }}>{item.reason}</Typography>}
    {item.authorId && <Box component="details"><Typography component="summary" variant="body2">Технические сведения</Typography><Typography variant="caption" sx={{ userSelect: 'text' }}>ID автора: {item.authorId}</Typography></Box>}
  </Stack>;
}

export function VersionHistory({ title, items, currentVersion, onOpen }: {
  title: string; items: RevisionMetadata[]; currentVersion: number; onOpen: (version: number) => void;
}) {
  const desktop = useMediaQuery(useTheme().breakpoints.up('lg'));
  const size = desktop ? 20 : 5;
  const sorted = useMemo(() => [...items].sort((a, b) => Number(b.version === currentVersion) - Number(a.version === currentVersion) || b.version - a.version), [items, currentVersion]);
  const [page, setPage] = useState(0);
  const list = useRef<HTMLDivElement>(null);
  const heading = useId();
  const anchor = useRef<{ version: number; offset: number } | null>(null);
  const signature = sorted.map((item) => item.version).join(',');
  const pages = Math.ceil(sorted.length / size);
  const safePage = Math.min(page, Math.max(0, pages - 1));
  const remember = () => {
    const container = list.current;
    if (!container) return;
    const top = container.getBoundingClientRect().top;
    const rows = Array.from(container.querySelectorAll<HTMLElement>('[data-revision]'));
    const row = desktop ? rows.find((element) => element.getBoundingClientRect().bottom > top + 1) : rows[0];
    if (row) anchor.current = { version: Number(row.dataset.revision), offset: row.getBoundingClientRect().top - top };
  };
  useLayoutEffect(() => {
    const saved = anchor.current;
    if (!saved) { anchor.current = sorted[0] ? { version: sorted[0].version, offset: 0 } : null; return; }
    const index = sorted.findIndex((item) => item.version === saved.version);
    const nextPage = index < 0 ? Math.min(page, Math.max(0, pages - 1)) : Math.floor(index / size);
    if (nextPage !== page) { setPage(nextPage); return; }
    if (desktop && list.current) {
      const row = list.current.querySelector<HTMLElement>(`[data-revision="${saved.version}"]`);
      if (row) list.current.scrollTop += row.getBoundingClientRect().top - list.current.getBoundingClientRect().top - saved.offset;
    }
  }, [signature, size, desktop, sorted, page, pages]);
  const navigate = (next: number) => {
    anchor.current = { version: sorted[next * size].version, offset: 0 };
    setPage(next);
    if (list.current) list.current.scrollTop = 0;
  };
  return <Paper component="section" aria-labelledby={heading} sx={{ p: { xs: 3, md: 4 } }}>
    <Typography id={heading} data-history-heading tabIndex={-1} variant="h6" component="h2">{title}</Typography>
    <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>Всего: {items.length} · Действует версия {currentVersion}</Typography>
    {!items.length && <Typography color="text.secondary">История пока пуста</Typography>}
    <Box ref={list} role="region" aria-label={`${title}: список`} tabIndex={desktop && items.length > 1 ? 0 : undefined} onScroll={remember}
      sx={{ maxHeight: desktop && items.length > 1 ? 'clamp(180px, calc(100dvh - 360px), 520px)' : undefined, overflowY: desktop ? 'auto' : 'visible', pr: desktop ? 1 : 0, '&:focus-visible': { outline: '3px solid', outlineColor: 'info.main', outlineOffset: 3 } }}>
      {sorted.slice(safePage * size, (safePage + 1) * size).map((item) => <Box key={item.version} data-revision={item.version} sx={{ py: 1.5, borderBottom: 1, borderColor: 'divider', overflowWrap: 'anywhere' }}>
        <Stack direction="row" gap={1} alignItems="center" flexWrap="wrap"><Typography fontWeight={700}>Версия {item.version}</Typography>{item.version === currentVersion && <Chip label="Действует" size="small" color="success" />}</Stack>
        <Typography variant="caption" color="text.secondary">{fullTime.format(new Date(item.createdAt))} МСК</Typography>
        <Typography variant="body2" noWrap title={author(item)}>{author(item)}</Typography>
        {item.reason && <Typography variant="body2" color="text.secondary" sx={{ display: '-webkit-box', WebkitLineClamp: 2, WebkitBoxOrient: 'vertical', overflow: 'hidden' }}>{item.reason}</Typography>}
        <Button variant="text" size="small" startIcon={<VisibilityOutlinedIcon />} sx={{ mt: 0.5, minHeight: 44 }} aria-label={`Посмотреть версию ${item.version}`} onClick={() => { remember(); onOpen(item.version); }}>Посмотреть</Button>
      </Box>)}
    </Box>
    {pages > 1 && <Stack component="nav" aria-label={`${title}: страницы`} gap={1} sx={{ pt: 2 }}>
      <Typography variant="caption" color="text.secondary">{safePage * size + 1}–{Math.min((safePage + 1) * size, sorted.length)} из {sorted.length}</Typography>
      <Stack direction="row" justifyContent="space-between" gap={1}><Button disabled={safePage === 0} onClick={() => navigate(safePage - 1)}>Назад</Button><Button disabled={safePage + 1 >= pages} onClick={() => navigate(safePage + 1)}>Далее</Button></Stack>
    </Stack>}
  </Paper>;
}

export function VersionPreviewDialog({ title, onClose, metadata, currentVersion, children }: {
  title: string; onClose: () => void; metadata?: RevisionMetadata; currentVersion?: number; children: ReactNode;
}) {
  const opener = useRef(document.activeElement as HTMLElement | null);
  const fallback = useRef(opener.current?.closest('section')?.querySelector<HTMLElement>('[data-history-heading]'));
  useEffect(() => () => { queueMicrotask(() => (opener.current?.isConnected ? opener.current : fallback.current)?.focus()); }, []);
  return <Dialog open onClose={onClose} fullWidth maxWidth="md" disableRestoreFocus PaperProps={{ sx: { maxHeight: 'calc(100dvh - 32px)', m: 2, width: 'calc(100% - 32px)' } }}>
    <DialogTitle>{title}</DialogTitle>
    <DialogContent dividers>
      {metadata && currentVersion !== undefined && <Stack direction="row" gap={1} flexWrap="wrap" sx={{ mb: 2 }}><Chip size="small" label={metadata.version === currentVersion ? 'Действует' : 'Архивная'} />{metadata.version !== currentVersion && <Typography variant="body2">Сейчас действует версия {currentVersion}</Typography>}</Stack>}
      <Typography color="text.secondary" sx={{ mb: 2 }}>Сохранённые условия. Просмотр без редактирования.</Typography>
      {metadata && <Box sx={{ mb: 3 }}><VersionMetadata item={metadata} /></Box>}
      {children}
    </DialogContent>
    <DialogActions><Button onClick={onClose}>Закрыть</Button></DialogActions>
  </Dialog>;
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
