import NotificationsNoneRoundedIcon from '@mui/icons-material/NotificationsNoneRounded';
import { Alert, Badge, Box, Button, CircularProgress, IconButton, Popover, Stack, Typography } from '@mui/material';
import { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { notificationsApi } from '../api/client';
import { visuallyHidden } from '../theme/visuallyHidden';
import { CabinetNotification } from '../types';

const REFRESH_MS = 30_000;

export function formatNotificationTime(iso: string) {
  const date = new Date(iso);
  const minutes = Math.round((Date.now() - date.getTime()) / 60_000);
  if (minutes < 1) return 'только что';
  if (minutes < 60) return `${minutes} мин назад`;
  if (minutes < 24 * 60) return `${Math.round(minutes / 60)} ч назад`;
  return date.toLocaleDateString('ru-RU', { day: 'numeric', month: 'long' });
}

/** One notification as a button: opening it marks it read and goes where it points. */
export function NotificationItem({ item, onOpen }: { item: CabinetNotification; onOpen: (item: CabinetNotification) => void }) {
  return (
    <Box
      component="button"
      type="button"
      onClick={() => onOpen(item)}
      sx={{
        display: 'block',
        width: '100%',
        minHeight: 44,
        textAlign: 'left',
        border: 0,
        borderRadius: 1.5,
        px: 1.5,
        py: 1.25,
        cursor: 'pointer',
        font: 'inherit',
        color: 'text.primary',
        bgcolor: item.read ? 'transparent' : 'action.selected',
        '&:hover': { bgcolor: 'action.hover' },
        '&:focus-visible': { outline: '2px solid', outlineColor: 'primary.main', outlineOffset: 2 }
      }}
    >
      <Typography variant="body2" fontWeight={item.read ? 500 : 700} sx={{ overflowWrap: 'anywhere' }}>
        {!item.read && <Box component="span" sx={visuallyHidden}>Новое: </Box>}
        {item.title}
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ overflowWrap: 'anywhere' }}>{item.body}</Typography>
      <Typography variant="caption" color="text.secondary" component="time" dateTime={item.createdAt}>{formatNotificationTime(item.createdAt)}</Typography>
    </Box>
  );
}

/**
 * The bell in the header. The count is refreshed every 30 seconds and when the window comes back
 * into focus; the list of the latest ten is loaded when opened.
 */
export function NotificationBell() {
  const navigate = useNavigate();
  const anchor = useRef<HTMLButtonElement>(null);
  const [open, setOpen] = useState(false);
  const [unread, setUnread] = useState(0);
  const [items, setItems] = useState<CabinetNotification[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  const refreshCount = useCallback(() => {
    notificationsApi.unreadCount().then(setUnread).catch(() => undefined);
  }, []);

  useEffect(() => {
    refreshCount();
    const timer = window.setInterval(refreshCount, REFRESH_MS);
    window.addEventListener('focus', refreshCount);
    return () => {
      window.clearInterval(timer);
      window.removeEventListener('focus', refreshCount);
    };
  }, [refreshCount]);

  async function load() {
    setError(null);
    try {
      const page = await notificationsApi.page(0, 10);
      setItems(page.items);
      setUnread(page.unread);
    } catch (reason) {
      setError(reason instanceof Error && reason.message ? reason.message : 'Не удалось загрузить уведомления');
    }
  }

  function toggle() {
    const next = !open;
    setOpen(next);
    if (next) void load();
  }

  async function openItem(item: CabinetNotification) {
    setOpen(false);
    if (!item.read) {
      setUnread((value) => Math.max(0, value - 1));
      await notificationsApi.markRead(item.id).catch(() => undefined);
    }
    if (item.link) navigate(item.link);
  }

  async function readAll() {
    await notificationsApi.markAllRead().catch(() => undefined);
    setUnread(0);
    setItems((current) => current?.map((item) => ({ ...item, read: true })) ?? null);
  }

  const label = unread > 0 ? `Уведомления: ${unread} непрочитанных` : 'Уведомления: нет новых';
  return (
    <>
      <IconButton ref={anchor} onClick={toggle} aria-label={label} aria-haspopup="dialog" aria-expanded={open}>
        <Badge badgeContent={unread} color="error" max={99}>
          <NotificationsNoneRoundedIcon aria-hidden />
        </Badge>
      </IconButton>
      <Popover
        open={open}
        anchorEl={anchor.current}
        onClose={() => setOpen(false)}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'right' }}
        transformOrigin={{ vertical: 'top', horizontal: 'right' }}
        slotProps={{ paper: { role: 'dialog', 'aria-label': 'Уведомления', sx: { width: 'min(380px, calc(100vw - 32px))', maxHeight: '70vh', p: 1 } } }}
      >
        <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', px: 1, pb: 0.5, gap: 1 }}>
          <Typography component="h2" variant="subtitle1" fontWeight={700}>Уведомления</Typography>
          {unread > 0 && <Button size="small" onClick={() => void readAll()} sx={{ minHeight: 44 }}>Прочитать все</Button>}
        </Box>
        {error && <Alert severity="error" sx={{ m: 1 }}>{error}</Alert>}
        {!items && !error && <CircularProgress size={20} aria-label="Загрузка уведомлений" sx={{ display: 'block', m: 2 }} />}
        {items && items.length === 0 && (
          <Typography color="text.secondary" sx={{ p: 1.5 }}>
            Пока ничего. Здесь появятся запросы банка, решения по заявкам и новости о выдаче.
          </Typography>
        )}
        {items && items.length > 0 && (
          <Stack component="ul" spacing={0.5} sx={{ listStyle: 'none', p: 0, m: 0 }}>
            {items.map((item) => (
              <li key={item.id}><NotificationItem item={item} onOpen={(chosen) => void openItem(chosen)} /></li>
            ))}
          </Stack>
        )}
      </Popover>
    </>
  );
}
