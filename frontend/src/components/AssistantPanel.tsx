import { Alert, Badge, Box, Button, Chip, Dialog, DialogActions, DialogContent, DialogTitle, Drawer, Fab, IconButton, Stack, TextField, Typography } from '@mui/material';
import ChatBubbleOutlineRoundedIcon from '@mui/icons-material/ChatBubbleOutlineRounded';
import CloseRoundedIcon from '@mui/icons-material/CloseRounded';
import { FormEvent, useEffect, useRef, useState } from 'react';
import { Link as RouterLink, useLocation, useNavigate } from 'react-router-dom';
import { api, assistantApi, get, post } from '../api/client';
import { readSession } from '../auth/session';
import { tokens } from '../theme/theme';
import { SensitiveDataNotice } from './SensitiveDataNotice';
import { AssistantAnswer, AssistantTurn } from '../types';

const APPLICATION_PATH = /^\/(?:client|agent)\/applications\/([0-9a-f-]{36})(?:\/|$)/;
const AGENT_COMPANY_PATH = /^\/agent\/clients\/([0-9a-f-]{36})(?:\/|$)/;

interface Hint {
  type: 'MISSING_DOCUMENTS' | 'INFO_DEADLINE' | 'OFFER_DEADLINE' | 'PRE_APPROVED' | 'ALTERNATIVE';
  title: string;
  detail: string;
  applicationId: string | null;
  deadline: string | null;
  canRemind: boolean;
  companyId?: string | null;
  infoRequestId?: string | null;
}

interface DraftPreview {
  companyId: string | null;
  input: { productCode: string; purpose: string; amount: number; termMonths: number };
  productName: string;
}

const deadlineFormat = new Intl.DateTimeFormat('ru-RU', { dateStyle: 'medium', timeStyle: 'short', timeZone: 'Europe/Moscow' });

type StreamProgress = { type: 'status'; text: string } | { type: 'reference'; text: string; source: string };

async function readAssistantStream(
  question: string, applicationId: string | null, history: AssistantTurn[], signal: AbortSignal, showProgress: (progress: StreamProgress) => void
): Promise<AssistantAnswer> {
  const { data } = await api.post<ReadableStream<Uint8Array>>('/assistant/messages/stream', { question, applicationId, history }, {
    adapter: 'fetch', responseType: 'stream', signal, headers: { Accept: 'text/event-stream' }
  });
  const reader = data.getReader();
  const decoder = new TextDecoder();
  let pending = '';
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) throw new Error('Ответ помощника оборвался. Повторите вопрос.');
      pending += decoder.decode(value, { stream: true });
      let boundary = pending.indexOf('\n\n');
      while (boundary >= 0) {
        const lines = pending.slice(0, boundary).split('\n');
        pending = pending.slice(boundary + 2);
        const type = lines.find((line) => line.startsWith('event: '))?.slice(7);
        const json = lines.find((line) => line.startsWith('data: '))?.slice(6);
        if (json) {
          const event = JSON.parse(json);
          if (type === 'status') showProgress({ type, text: event.text });
          if (type === 'reference') showProgress({ type, text: event.text, source: event.source });
          if (type === 'answer') return event as AssistantAnswer;
          if (type === 'failure') throw new Error(event.message);
        }
        boundary = pending.indexOf('\n\n');
      }
    }
  } finally {
    await reader.cancel().catch(() => undefined);
    reader.releaseLock();
  }
}

const suggestionsFor = (onApplication: boolean) =>
  onApplication
    ? ['Что сейчас с моей заявкой?', 'Почему банк предложил другие условия?', 'Какие документы нужно загрузить?', 'Почему отказали и что можно изменить?']
    : ['Чем овердрафт отличается от кредита?', 'Как считается платёж?', 'Какие документы понадобятся?', 'Сколько рассматривается заявка?'];

interface Message extends AssistantTurn {
  answer?: AssistantAnswer;
  completed?: boolean;
}

// The transcript stays visible; only successful, bounded turns enter the model context.
const contextFor = (messages: Message[]): AssistantTurn[] => messages
  .filter((message) => message.completed)
  .slice(-4)
  .map(({ role, text }) => ({ role, text: text.slice(0, 1000) }));

/**
 * The assistant: explains products, statuses and the bank's decisions in plain words. On an
 * application's page it answers about that application, from what the client may see of it.
 * Answers are plain text: nothing the model writes is rendered as markup.
 */
export function AssistantPanel() {
  const location = useLocation();
  const navigate = useNavigate();
  const applicationId = location.pathname.match(APPLICATION_PATH)?.[1] ?? null;
  const companyId = location.pathname.match(AGENT_COMPANY_PATH)?.[1] ?? null;
  const agent = location.pathname === '/agent' || location.pathname.startsWith('/agent/');
  const contextKey = `${agent ? 'agent' : 'client'}:${applicationId ?? ''}:${companyId ?? ''}`;
  const [open, setOpen] = useState(false);
  const [mode, setMode] = useState<{ mode: 'MODEL' | 'RULES'; model: string | null } | null>(null);
  const [messages, setMessages] = useState<Message[]>([]);
  const [question, setQuestion] = useState('');
  const [busy, setBusy] = useState(false);
  const [progress, setProgress] = useState<StreamProgress | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [hints, setHints] = useState<Hint[]>([]);
  const [confirmation, setConfirmation] = useState<Hint | null>(null);
  const [sendingReminder, setSendingReminder] = useState(false);
  const [reminderFeedback, setReminderFeedback] = useState<{ error: boolean; text: string } | null>(null);
  const [draftPreview, setDraftPreview] = useState<DraftPreview | null>(null);
  const [draftBusy, setDraftBusy] = useState(false);
  const [draftError, setDraftError] = useState<string | null>(null);
  const end = useRef<HTMLDivElement>(null);
  const requestId = useRef(0);
  const reminderSequence = useRef(0);
  const currentContext = useRef(contextKey);
  currentContext.current = contextKey;
  const inFlight = useRef<AbortController | null>(null);

  useEffect(() => {
    if (open && !mode) assistantApi.status().then(setMode).catch(() => setMode(null));
  }, [open, mode]);

  useEffect(() => {
    end.current?.scrollIntoView?.({ block: 'end' });
  }, [messages, busy, progress]);

  // Another application is another conversation.
  useEffect(() => {
    requestId.current += 1;
    setMessages([]);
    setQuestion('');
    setError(null);
    setProgress(null);
    setBusy(false);
    setConfirmation(null);
    setSendingReminder(false);
    setReminderFeedback(null);
    setDraftPreview(null);
    setDraftBusy(false);
    setDraftError(null);
    return () => {
      requestId.current += 1;
      reminderSequence.current += 1;
      inFlight.current?.abort();
    };
  }, [contextKey]);

  useEffect(() => {
    setHints([]);
    if ((!agent && !applicationId) || !readSession()) return;
    let active = true;
    const refresh = () => get<Hint[]>('/assistant/next-steps', applicationId ? { applicationId } : undefined).then((value) => {
      if (active) setHints(value);
    }).catch(() => { if (active) setHints([]); });
    void refresh();
    const interval = window.setInterval(() => { void refresh(); }, 60_000);
    return () => { active = false; window.clearInterval(interval); };
  }, [agent, applicationId, companyId]);

  const ask = async (text: string) => {
    const trimmed = text.trim();
    if (!trimmed || busy) return;
    const request = ++requestId.current;
    const isCurrent = () => request === requestId.current && contextKey === currentContext.current;
    const controller = new AbortController();
    inFlight.current = controller;
    const history = contextFor(messages);
    const last = messages.at(-1);
    const retry = last?.role === 'user' && !last.completed && last.text === trimmed;
    const questionIndex = retry ? messages.length - 1 : messages.length;
    if (!retry) setMessages((current) => [...current, { role: 'user', text: trimmed }]);
    setQuestion('');
    setBusy(true);
    setError(null);
    setProgress(null);
    try {
      const answer = mode?.mode === 'MODEL' && readSession() && typeof ReadableStream !== 'undefined'
        ? await readAssistantStream(trimmed, applicationId, history, controller.signal, (update) => { if (isCurrent()) setProgress(update); })
        : await assistantApi.ask(trimmed, applicationId, history);
      if (isCurrent()) setMessages((current) => [
        ...current.map((message, index) => index === questionIndex ? { ...message, completed: true } : message),
        { role: 'assistant', text: answer.text, answer, completed: true }
      ]);
    } catch (nextError) {
      if (isCurrent()) setError(nextError instanceof Error ? nextError.message : 'Помощник сейчас недоступен');
    } finally {
      if (isCurrent()) { setBusy(false); setProgress(null); }
    }
  };

  const sendReminder = async () => {
    if (!confirmation?.applicationId || !confirmation.infoRequestId || sendingReminder) return;
    const request = ++reminderSequence.current;
    const isCurrent = () => request === reminderSequence.current && contextKey === currentContext.current;
    setSendingReminder(true);
    setReminderFeedback(null);
    try {
      await post('/assistant/reminders', { applicationId: confirmation.applicationId, infoRequestId: confirmation.infoRequestId });
      if (!isCurrent()) return;
      setConfirmation(null);
      setReminderFeedback({ error: false, text: 'Клиенту отправлено уведомление' });
    } catch (nextError) {
      if (!isCurrent()) return;
      setConfirmation(null);
      setReminderFeedback({ error: true, text: nextError instanceof Error ? nextError.message : 'Напоминание не отправлено' });
    } finally {
      if (isCurrent()) setSendingReminder(false);
    }
  };

  const prepareDraft = async (path: string) => {
    if (draftBusy) return;
    setDraftError(null);
    setDraftBusy(true);
    try {
      const query = new URL(path, window.location.origin).searchParams;
      const purpose = query.get('purpose');
      const amount = Number(query.get('amount'));
      const termMonths = Number(query.get('termMonths'));
      const excluded = query.get('excludeProductCode');
      if (!purpose || !Number.isSafeInteger(amount) || amount <= 0 || !Number.isInteger(termMonths) || termMonths < 1) {
        throw new Error('Уточните цель, сумму и срок, чтобы подготовить черновик');
      }
      const target = agent
        ? companyId ?? (applicationId ? (await get<{ company: { id: string } }>(`/agent/applications/${applicationId}`)).company.id : null)
        : null;
      if (agent && !target) throw new Error('Сначала откройте клиента в агентском кабинете');
      const matches = await post<Array<{ fits: boolean; termMonths: number; product: { code: string; name: string } }>>('/products/match', { purpose, amount, termMonths });
      const match = matches.find(({ fits, product }) => fits && product.code !== excluded);
      if (!match) throw new Error('Для этих параметров пока нет подходящего продукта');
      if (contextKey === currentContext.current) setDraftPreview({ companyId: target, productName: match.product.name,
        input: { productCode: match.product.code, purpose, amount, termMonths: match.termMonths } });
    } catch (nextError) {
      if (contextKey === currentContext.current) setDraftError(nextError instanceof Error ? nextError.message : 'Черновик недоступен');
    } finally {
      if (contextKey === currentContext.current) setDraftBusy(false);
    }
  };

  const createDraft = async () => {
    if (!draftPreview || draftBusy) return;
    setDraftBusy(true);
    setDraftError(null);
    try {
      const draft = draftPreview.companyId
        ? await post<{ id: string }>('/agent/applications', { ...draftPreview.input, companyId: draftPreview.companyId })
        : await post<{ id: string }>('/applications', { ...draftPreview.input, bureauConsent: false });
      if (contextKey !== currentContext.current) return;
      setDraftPreview(null);
      setOpen(false);
      navigate(`/${draftPreview.companyId ? 'agent' : 'client'}/applications/${draft.id}`);
    } catch (nextError) {
      if (contextKey === currentContext.current) setDraftError(nextError instanceof Error ? nextError.message : 'Черновик не создан');
    } finally {
      if (contextKey === currentContext.current) setDraftBusy(false);
    }
  };

  const submit = (event: FormEvent) => {
    event.preventDefault();
    void ask(question);
  };

  return (
    <>
      <Fab variant="extended" color="secondary" onClick={() => setOpen(true)} aria-label="Открыть помощника"
        sx={{ position: 'fixed', right: { xs: 16, md: 32 }, bottom: { xs: 16, md: 32 }, textTransform: 'none', zIndex: 1200 }}>
        <Badge color="error" badgeContent={hints.length} sx={{ mr: 1.5 }}><ChatBubbleOutlineRoundedIcon /></Badge>
        Помощник
      </Fab>
      {/* No scroll lock: removing the page's scrollbar would shift the whole page sideways. */}
      <Drawer anchor="right" open={open} onClose={() => setOpen(false)} disableScrollLock PaperProps={{ sx: { width: { xs: '100%', sm: 420 }, display: 'flex', flexDirection: 'column' } }}>
        <Stack direction="row" alignItems="center" justifyContent="space-between" sx={{ p: 2, borderBottom: 1, borderColor: 'divider' }}>
          <Box>
            <Typography variant="subtitle1" component="h2" fontWeight={700}>Помощник</Typography>
            <Typography variant="caption" color="text.secondary">
              {mode?.mode === 'MODEL' ? `Доступна модель с открытыми весами ${mode.model}` : 'Справочный режим: ответы по правилам и данным заявки'}
              {applicationId ? ' · про эту заявку' : ''}
            </Typography>
          </Box>
          <IconButton onClick={() => setOpen(false)} aria-label="Закрыть помощника"><CloseRoundedIcon /></IconButton>
        </Stack>

        <Box sx={{ flex: 1, overflowY: 'auto', p: 2 }} aria-live="polite">
          {hints.length > 0 && <Stack spacing={1} sx={{ mb: 2 }}>
            {hints.map((hint, index) => <Box key={`${hint.type}:${hint.applicationId ?? index}`} sx={{ p: 1.5, bgcolor: tokens.secondaryFill, borderRadius: 2 }}>
              <Typography fontWeight={700}>{hint.title}</Typography>
              <Typography variant="body2" sx={{ whiteSpace: 'pre-line' }}>{hint.detail}</Typography>
              {hint.deadline && <Typography variant="caption" display="block">До {deadlineFormat.format(new Date(hint.deadline))} МСК</Typography>}
              <Stack direction="row" flexWrap="wrap" gap={1} sx={{ mt: 0.5 }}>
                {hint.applicationId && hint.applicationId !== applicationId && <Button component={RouterLink} to={`/${agent ? 'agent' : 'client'}/applications/${hint.applicationId}`} size="small" onClick={() => setOpen(false)}>Открыть заявку</Button>}
                {hint.type === 'PRE_APPROVED' && hint.companyId && <Button component={RouterLink} to={`/agent/clients/${hint.companyId}`} size="small" onClick={() => setOpen(false)}>Открыть клиента</Button>}
                {hint.type === 'ALTERNATIVE' && applicationId === hint.applicationId && <Button size="small" disabled={busy} onClick={() => void ask('Подбери альтернативный продукт и посчитай платёж')}>Сравнить продукты</Button>}
                {hint.canRemind && hint.applicationId && hint.infoRequestId && <Button size="small" onClick={() => setConfirmation(hint)}>Напомнить клиенту</Button>}
              </Stack>
            </Box>)}
          </Stack>}
          {reminderFeedback && <Alert severity={reminderFeedback.error ? 'error' : 'success'} sx={{ mb: 2 }}>{reminderFeedback.text}</Alert>}
          {draftError && !draftPreview && <Alert severity="error" sx={{ mb: 2 }}>{draftError}</Alert>}
          {messages.length === 0 && (
            <Stack spacing={1.5}>
              <Typography color="text.secondary">
                Объясню продукты и термины, что происходит с заявкой и что означает решение банка. Решения принимает банк — я только поясняю.
              </Typography>
              <Stack direction="row" flexWrap="wrap" gap={1}>
                {suggestionsFor(!!applicationId).map((suggestion) => (
                  <Chip key={suggestion} label={suggestion} onClick={() => void ask(suggestion)} sx={{ height: 'auto', py: 0.75, '& .MuiChip-label': { whiteSpace: 'normal' } }} />
                ))}
              </Stack>
            </Stack>
          )}
          <Stack spacing={1.5}>
            {messages.map((message, index) => (
              <Box key={index} sx={{ alignSelf: message.role === 'user' ? 'flex-end' : 'flex-start', maxWidth: '88%' }}>
                <Box sx={{ px: 2, py: 1.25, borderRadius: '16px', bgcolor: message.role === 'user' ? tokens.dark : tokens.secondaryFill, color: message.role === 'user' ? '#fff' : 'text.primary' }}>
                  <Typography sx={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{message.text}</Typography>
                </Box>
                {message.answer?.link && (
                  agent || new URL(message.answer.link.path, window.location.origin).searchParams.has('excludeProductCode')
                    ? <Button variant="contained" size="small" disabled={draftBusy} sx={{ mt: 1 }} onClick={() => void prepareDraft(message.answer!.link!.path)}>Подготовить черновик</Button>
                    : <Button component={RouterLink} to={message.answer.link.path} variant="contained" size="small" sx={{ mt: 1, display: 'flex', width: 'fit-content' }}>{message.answer.link.label}</Button>
                )}
                {message.answer && <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 0.5 }}>
                  {message.answer.mode === 'MODEL' ? 'Ответ модели' : 'По правилам и данным кабинета'}
                </Typography>}
                {message.answer && message.answer.sources.length > 0 && <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>
                  Источники: {message.answer.sources.join(', ')}
                </Typography>}
              </Box>
            ))}
            {busy && (
              <Box role="status">
                {progress?.type === 'reference' && (
                  <Typography variant="caption" color="text.secondary">Из справки · {progress.source}</Typography>
                )}
                <Typography color="text.secondary" sx={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>
                  {progress?.text ?? 'Помощник печатает…'}
                </Typography>
              </Box>
            )}
          </Stack>
          <div ref={end} />
        </Box>

        <Box sx={{ px: 2, pt: 1 }}><SensitiveDataNotice compact /></Box>
        {error && <Alert severity="error" sx={{ mx: 2 }} action={<Button disabled={busy} onClick={() => void ask(messages.at(-1)?.text ?? '')}>Повторить вопрос</Button>}>{error}</Alert>}
        <Stack component="form" onSubmit={submit} direction="row" spacing={1} sx={{ p: 2, borderTop: 1, borderColor: 'divider' }}>
          <TextField label="Ваш вопрос" value={question} onChange={(event) => setQuestion(event.target.value)} onBlur={() => setQuestion((value) => value.trim())} fullWidth size="small" inputProps={{ maxLength: 500 }} />
          <Button type="submit" variant="contained" disabled={busy || !question.trim()}>Спросить</Button>
        </Stack>
      </Drawer>
      <Dialog open={confirmation !== null} onClose={() => { if (!sendingReminder) setConfirmation(null); }}>
        <DialogTitle>Напомнить клиенту о документах?</DialogTitle>
        <DialogContent>
          <Typography sx={{ whiteSpace: 'pre-line' }}>{confirmation?.detail}</Typography>
          <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>Клиент получит уведомление со ссылкой на заявку.</Typography>
        </DialogContent>
        <DialogActions>
          <Button disabled={sendingReminder} onClick={() => setConfirmation(null)}>Отмена</Button>
          <Button variant="contained" disabled={sendingReminder} onClick={() => void sendReminder()}>Отправить уведомление</Button>
        </DialogActions>
      </Dialog>
      <Dialog open={draftPreview !== null} onClose={() => { if (!draftBusy) setDraftPreview(null); }}>
        <DialogTitle>{draftPreview?.companyId ? 'Создать черновик для клиента?' : 'Создать черновик заявки?'}</DialogTitle>
        {draftPreview && <DialogContent>
          <Typography>{draftPreview.productName}</Typography>
          <Typography>{draftPreview.input.amount.toLocaleString('ru-RU')} ₽ на {draftPreview.input.termMonths} мес.</Typography>
          {draftError && <Alert severity="error" sx={{ mt: 1 }}>{draftError}</Alert>}
        </DialogContent>}
        <DialogActions>
          <Button disabled={draftBusy} onClick={() => setDraftPreview(null)}>Отмена</Button>
          <Button variant="contained" disabled={draftBusy} onClick={() => void createDraft()}>Создать черновик</Button>
        </DialogActions>
      </Dialog>
    </>
  );
}
