import AttachFileOutlinedIcon from '@mui/icons-material/AttachFileOutlined';
import { Alert, Box, Button, Chip, Dialog, DialogContent, DialogTitle, Paper, Stack, TextField, Typography } from '@mui/material';
import { FormEvent, useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { applicationsApi, chatApi } from '../api/client';
import { useAuth } from '../auth/AuthProvider';
import { FINISHED_STATUSES } from '../shared/applicationText';
import { fileSize, formatDateTime } from '../shared/format';
import { roleLabels } from '../shared/labels';
import { ApplicationStatus, ChatAudit, ChatFile, ChatMessage, ChatPage, Role } from '../types';
import { LoadingBlock } from './LoadingBlock';

const POLL_MS = 5000;
const actionTitles = { MESSAGE_SENT: 'Отправлено сообщение', ATTACHMENT_UPLOADED: 'Загружено вложение', READ_CURSOR_ADVANCED: 'Прочитаны сообщения' };
const merge = (current: ChatMessage[], incoming: ChatMessage[]) => [...new Map([...current, ...incoming].map((message) => [message.id, message])).values()].sort((a, b) => a.seq - b.seq);

/** Conversation and read receipts are independent from the application's process journal. */
export function MessagesBlock({ applicationId, status }: { applicationId: string; status: ApplicationStatus }) {
  const { session } = useAuth();
  const bank = session?.user.role !== 'CLIENT';
  const [page, setPage] = useState<ChatPage | null>(null);
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [text, setText] = useState('');
  const [files, setFiles] = useState<ChatFile[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [sending, setSending] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [failedUpload, setFailedUpload] = useState<{ file: File; key: string } | null>(null);
  const [loadingOlder, setLoadingOlder] = useState(false);
  const [endVisible, setEndVisible] = useState(false);
  const [active, setActive] = useState(() => document.visibilityState === 'visible');
  const [audit, setAudit] = useState<ChatAudit[] | null>(null);
  const [auditOpen, setAuditOpen] = useState(false);
  const [auditLoading, setAuditLoading] = useState(false);
  const currentSeq = useRef(0);
  const messageKey = useRef(crypto.randomUUID());
  const historyBox = useRef<HTMLDivElement>(null);
  const end = useRef<HTMLDivElement>(null);
  const atBottom = useRef(true);
  const prepend = useRef<{ height: number; top: number } | null>(null);
  const reading = useRef(false);
  const ticket = useRef(0);
  const writable = page?.writable && status !== 'DRAFT' && !FINISHED_STATUSES.includes(status);

  const accept = useCallback((next: ChatPage) => {
    currentSeq.current = Math.max(currentSeq.current, ...next.messages.map((message) => message.seq));
    setMessages((previous) => merge(previous, next.messages));
    setPage((previous) => previous && previous.readSeq > next.readSeq ? { ...next, readSeq: previous.readSeq, unreadCount: previous.unreadCount } : next);
  }, []);
  const load = useCallback(async () => {
    const request = ++ticket.current;
    try {
      const result = await chatApi.page(applicationId, currentSeq.current ? { after: currentSeq.current } : {});
      if (request === ticket.current) accept(result);
    } catch (next) {
      if (request === ticket.current) setError(next instanceof Error ? next.message : 'Не удалось загрузить переписку');
    }
  }, [applicationId, accept]);
  const cancelPending = useCallback(() => { ticket.current += 1; }, []);
  useEffect(() => {
    void load();
    const timer = window.setInterval(() => void load(), POLL_MS);
    return () => { cancelPending(); window.clearInterval(timer); };
  }, [load, status, cancelPending]);
  useEffect(() => {
    const observer = new IntersectionObserver(([entry]) => setEndVisible(entry.isIntersecting));
    if (end.current) observer.observe(end.current);
    const focus = () => setActive(document.visibilityState === 'visible');
    document.addEventListener('visibilitychange', focus);
    return () => { observer.disconnect(); document.removeEventListener('visibilitychange', focus); };
  }, []);
  useLayoutEffect(() => {
    const box = historyBox.current;
    if (!box) return;
    if (prepend.current) {
      box.scrollTop = box.scrollHeight - prepend.current.height + prepend.current.top;
      prepend.current = null;
    } else if (atBottom.current) box.scrollTop = box.scrollHeight;
  }, [messages]);
  const lastDisplayed = messages.at(-1)?.seq ?? 0;
  useEffect(() => {
    if (!page || page.hasNewer || !active || !endVisible || lastDisplayed <= page.readSeq || reading.current) return;
    reading.current = true;
    void chatApi.read(applicationId, lastDisplayed).then((read) => {
      setPage((current) => current && { ...current, readSeq: Math.max(current.readSeq, read.readSeq), unreadCount: read.unreadCount });
    }).catch((next) => setError(next instanceof Error ? next.message : 'Не удалось отметить сообщения прочитанными'))
      .finally(() => { reading.current = false; });
  }, [applicationId, page, active, endVisible, lastDisplayed]);

  const older = async () => {
    if (loadingOlder || !messages[0]) return;
    setLoadingOlder(true);
    try {
      const result = await chatApi.page(applicationId, { before: messages[0].seq });
      if (historyBox.current) prepend.current = { height: historyBox.current.scrollHeight, top: historyBox.current.scrollTop };
      setMessages((current) => merge(current, result.messages));
    } catch (next) { setError(next instanceof Error ? next.message : 'Не удалось загрузить историю'); }
    finally { setLoadingOlder(false); }
  };
  const send = async (event: FormEvent) => {
    event.preventDefault();
    if (!writable || sending || uploading || failedUpload || (!text.trim() && files.length === 0)) return;
    setSending(true); setError(null);
    try {
      const sent = await chatApi.send(applicationId, messageKey.current, text, files.map((file) => file.id));
      atBottom.current = true;
      setMessages((current) => merge(current, [sent]));
      setText(''); setFiles([]); messageKey.current = crypto.randomUUID();
      await load();
    } catch (next) { setError(next instanceof Error ? next.message : 'Не удалось отправить сообщение. Повторите отправку.'); }
    finally { setSending(false); }
  };
  const upload = async (file: File, key: string = crypto.randomUUID()) => {
    setUploading(true); setError(null);
    try {
      const stored = await chatApi.upload(applicationId, key, file);
      setFiles((current) => current.some((entry) => entry.id === stored.id) ? current : [...current, stored]);
      messageKey.current = crypto.randomUUID(); setFailedUpload(null);
    } catch (next) { setFailedUpload({ file, key }); setError(next instanceof Error ? next.message : 'Не удалось загрузить вложение'); }
    finally { setUploading(false); }
  };
  const download = async (file: ChatFile) => {
    try { await applicationsApi.downloadDocument(applicationId, file.id, file.fileName); }
    catch (next) { setError(next instanceof Error ? next.message : 'Не удалось скачать вложение'); }
  };
  const openAudit = async () => {
    setAuditOpen(true); setAuditLoading(true);
    try { setAudit(await chatApi.audit(applicationId)); }
    catch (next) { setError(next instanceof Error ? next.message : 'Не удалось загрузить аудит'); setAuditOpen(false); }
    finally { setAuditLoading(false); }
  };

  return (
    <Paper component="section" sx={{ p: { xs: 3, md: 4 } }} aria-label="Переписка по заявке">
      <Stack spacing={2}>
        <Stack direction="row" flexWrap="wrap" alignItems="center" gap={1}>
          <Typography variant="h6" component="h2" sx={{ flexGrow: 1 }}>Сообщения</Typography>
          {bank && <Button onClick={() => void openAudit()}>Аудит переписки</Button>}
        </Stack>
        <Typography variant="body2" color="text.secondary">Переписка компании и банка. Текст и файлы не меняют условия, статус или комплектность заявки.</Typography>
        {error && <Alert severity="error" onClose={() => setError(null)}>{error}<Button onClick={() => { setError(null); void load(); }}>Обновить переписку</Button></Alert>}
        {(messages[0]?.seq ?? 0) > 1 && <Button disabled={loadingOlder} onClick={() => void older()}>Ранние сообщения</Button>}
        <Box ref={historyBox} sx={{ maxHeight: (theme) => theme.spacing(48), minHeight: (theme) => theme.spacing(12), overflowY: 'auto' }}
          onScroll={(event) => { const box = event.currentTarget; atBottom.current = box.scrollHeight - box.scrollTop - box.clientHeight < 24; }}>
          {!page ? <LoadingBlock /> : messages.length === 0 ? <Typography color="text.secondary">Сообщений пока нет.</Typography> : (
            <Stack component="ol" spacing={2} sx={{ listStyle: 'none', p: 0, m: 0 }} aria-label="Сообщения участников заявки">
              {messages.map((message) => <Box component="li" key={message.id} sx={{ p: 2, bgcolor: message.side === page.side ? 'action.hover' : 'background.default', borderRadius: 2 }}>
                <Typography variant="body2" fontWeight={600} sx={{ overflowWrap: 'anywhere' }}>{message.side === 'COMPANY' ? 'Компания' : 'Банк'} · {message.authorName}</Typography>
                <Typography variant="caption" color="text.secondary">{formatDateTime(message.createdAt)}</Typography>
                {message.text && <Typography sx={{ mt: 1, whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{message.text}</Typography>}
                {message.attachments.map((file) => <Button key={file.id} startIcon={<AttachFileOutlinedIcon />} onClick={() => void download(file)} sx={{ textAlign: 'left', overflowWrap: 'anywhere' }}>{file.fileName} · {fileSize(file.sizeBytes)}</Button>)}
              </Box>)}
            </Stack>
          )}
          <Box ref={end} sx={{ height: (theme) => theme.spacing(0.25) }} />
        </Box>
        {page && !writable && <Alert severity="info">{page.readOnlyReason ?? 'Переписка доступна только для чтения'}</Alert>}
        {writable && <Stack component="form" spacing={2} onSubmit={(event) => void send(event)}>
          <TextField label="Сообщение" multiline minRows={3} value={text} disabled={sending} inputProps={{ maxLength: 2000 }} helperText={`${text.length} из 2000 символов. Только простой текст.`}
            onChange={(event) => { setText(event.target.value); messageKey.current = crypto.randomUUID(); }} />
          {files.map((file) => <Chip key={file.id} label={file.fileName} sx={{ maxWidth: '100%', alignSelf: 'flex-start' }} disabled={sending || uploading} onDelete={() => { setFiles((current) => current.filter((entry) => entry.id !== file.id)); messageKey.current = crypto.randomUUID(); }} />)}
          {failedUpload && <Stack spacing={1}>
            <Typography color="text.secondary">Вложение не загружено. Повторите загрузку или отмените её перед отправкой.</Typography>
            <Button disabled={uploading || sending} onClick={() => void upload(failedUpload.file, failedUpload.key)}>Повторить загрузку</Button>
            <Button disabled={uploading || sending} onClick={() => { setFailedUpload(null); setError(null); }}>Отменить загрузку</Button>
          </Stack>}
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5}>
            <Button component="label" variant="outlined" startIcon={<AttachFileOutlinedIcon />} disabled={uploading || sending || files.length >= 5}>
              {uploading ? 'Загружаем вложение…' : 'Приложить файл'}
              <input type="file" hidden aria-label="Файл для сообщения" accept=".pdf,.jpg,.jpeg,.png,.doc,.docx,.xls,.xlsx" onChange={(event) => { const file = event.target.files?.[0]; event.target.value = ''; if (file) void upload(file); }} />
            </Button>
            <Button type="submit" variant="contained" disabled={sending || uploading || Boolean(failedUpload) || (!text.trim() && files.length === 0)}>Отправить сообщение</Button>
          </Stack>
          <Typography variant="caption" color="text.secondary">До пяти вложений. Файлы переписки не заменяют запрошенные банком документы.</Typography>
        </Stack>}
      </Stack>
      <Dialog open={auditOpen} onClose={() => setAuditOpen(false)} fullWidth maxWidth="sm">
        <DialogTitle>Аудит переписки</DialogTitle>
        <DialogContent><Stack spacing={2}>{auditLoading ? <LoadingBlock /> : audit?.length === 0 ? <Typography>Действий пока нет.</Typography> : audit?.map((entry) => <Box key={entry.id}>
          <Typography fontWeight={600}>{actionTitles[entry.action]}</Typography>
          <Typography variant="body2" sx={{ overflowWrap: 'anywhere' }}>{formatDateTime(entry.occurredAt)} · {roleLabels[entry.actorRole as Role] ?? 'Участник'} · {entry.actorId}</Typography>
          {entry.readSeq !== null && <Typography variant="body2">До сообщения № {entry.readSeq}</Typography>}
        </Box>)}</Stack></DialogContent>
      </Dialog>
    </Paper>
  );
}
