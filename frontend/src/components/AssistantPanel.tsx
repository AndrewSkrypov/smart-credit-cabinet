import { Alert, Box, Button, Chip, Drawer, Fab, IconButton, Stack, TextField, Typography } from '@mui/material';
import ChatBubbleOutlineRoundedIcon from '@mui/icons-material/ChatBubbleOutlineRounded';
import CloseRoundedIcon from '@mui/icons-material/CloseRounded';
import { FormEvent, useEffect, useRef, useState } from 'react';
import { useLocation } from 'react-router-dom';
import { assistantApi } from '../api/client';
import { tokens } from '../theme/theme';
import { SensitiveDataNotice } from './SensitiveDataNotice';
import { AssistantAnswer, AssistantTurn } from '../types';

const APPLICATION_PATH = /^\/client\/applications\/([0-9a-f-]{36})/;

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
  const applicationId = location.pathname.match(APPLICATION_PATH)?.[1] ?? null;
  const [open, setOpen] = useState(false);
  const [mode, setMode] = useState<{ mode: 'MODEL' | 'RULES'; model: string | null } | null>(null);
  const [messages, setMessages] = useState<Message[]>([]);
  const [question, setQuestion] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const end = useRef<HTMLDivElement>(null);
  const requestId = useRef(0);
  const currentApplication = useRef(applicationId);
  currentApplication.current = applicationId;

  useEffect(() => {
    if (open && !mode) assistantApi.status().then(setMode).catch(() => setMode(null));
  }, [open, mode]);

  useEffect(() => {
    end.current?.scrollIntoView?.({ block: 'end' });
  }, [messages, busy]);

  // Another application is another conversation.
  useEffect(() => {
    requestId.current += 1;
    setMessages([]);
    setQuestion('');
    setError(null);
    setBusy(false);
    return () => { requestId.current += 1; };
  }, [applicationId]);

  const ask = async (text: string) => {
    const trimmed = text.trim();
    if (!trimmed || busy) return;
    const request = ++requestId.current;
    const isCurrent = () => request === requestId.current && applicationId === currentApplication.current;
    const history = contextFor(messages);
    const last = messages.at(-1);
    const retry = last?.role === 'user' && !last.completed && last.text === trimmed;
    const questionIndex = retry ? messages.length - 1 : messages.length;
    if (!retry) setMessages((current) => [...current, { role: 'user', text: trimmed }]);
    setQuestion('');
    setBusy(true);
    setError(null);
    try {
      const answer = await assistantApi.ask(trimmed, applicationId, history);
      if (isCurrent()) setMessages((current) => [
        ...current.map((message, index) => index === questionIndex ? { ...message, completed: true } : message),
        { role: 'assistant', text: answer.text, answer, completed: true }
      ]);
    } catch (nextError) {
      if (isCurrent()) setError(nextError instanceof Error ? nextError.message : 'Помощник сейчас недоступен');
    } finally {
      if (isCurrent()) setBusy(false);
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
        <ChatBubbleOutlineRoundedIcon sx={{ mr: 1 }} />
        Помощник
      </Fab>
      <Drawer anchor="right" open={open} onClose={() => setOpen(false)} PaperProps={{ sx: { width: { xs: '100%', sm: 420 }, display: 'flex', flexDirection: 'column' } }}>
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
                {message.answer && <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 0.5 }}>
                  {message.answer.mode === 'MODEL' ? 'Ответ модели' : 'По правилам и данным кабинета'}
                </Typography>}
                {message.answer && message.answer.sources.length > 0 && <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>
                  Источники: {message.answer.sources.join(', ')}
                </Typography>}
              </Box>
            ))}
            {busy && <Typography color="text.secondary">Помощник печатает…</Typography>}
          </Stack>
          <div ref={end} />
        </Box>

        <Box sx={{ px: 2, pt: 1 }}><SensitiveDataNotice compact /></Box>
        {error && <Alert severity="error" sx={{ mx: 2 }} action={<Button disabled={busy} onClick={() => void ask(messages.at(-1)?.text ?? '')}>Повторить вопрос</Button>}>{error}</Alert>}
        <Stack component="form" onSubmit={submit} direction="row" spacing={1} sx={{ p: 2, borderTop: 1, borderColor: 'divider' }}>
          <TextField label="Ваш вопрос" value={question} onChange={(event) => setQuestion(event.target.value)} fullWidth size="small" inputProps={{ maxLength: 500 }} />
          <Button type="submit" variant="contained" disabled={busy || !question.trim()}>Спросить</Button>
        </Stack>
      </Drawer>
    </>
  );
}
