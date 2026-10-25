import { Alert, Box, Button, Chip, Collapse, Paper, Stack, Typography } from '@mui/material';
import ExpandMoreRoundedIcon from '@mui/icons-material/ExpandMoreRounded';
import { useState } from 'react';
import { assistantApi, underwritingApi } from '../../../api/client';
import { useApi } from '../../../hooks/useApi';
import { AdviceItem, AdviceKind, AssistantAnswer } from '../../../types';

const kinds: Record<AdviceKind, { label: string; color: 'error' | 'warning' | 'info' | 'success' | 'default' }> = {
  STOP: { label: 'Стоп-фактор', color: 'error' },
  RISK: { label: 'Риск', color: 'error' },
  CHECK: { label: 'Проверить', color: 'warning' },
  ASK: { label: 'Запросить', color: 'info' },
  OPTION: { label: 'Вариант условий', color: 'success' },
  INFO: { label: 'К сведению', color: 'default' }
};

function Item({ item }: { item: AdviceItem }) {
  const [open, setOpen] = useState(false);
  const kind = kinds[item.kind];
  return (
    <Box sx={{ py: 1.5, '& + &': { borderTop: 1, borderColor: 'divider' } }}>
      <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
        <Chip size="small" label={kind.label} color={kind.color} />
        <Typography fontWeight={600}>{item.title}</Typography>
      </Stack>
      <Typography color="text.secondary" sx={{ mt: 0.5 }}>{item.text}</Typography>
      {item.because.length > 0 && (
        <>
          <Button size="small" variant="text" onClick={() => setOpen(!open)} aria-expanded={open}
            endIcon={<ExpandMoreRoundedIcon sx={{ transform: open ? 'rotate(180deg)' : 'none', transition: 'transform 0.2s' }} />} sx={{ mt: 0.5, ml: -1 }}>
            Почему
          </Button>
          <Collapse in={open}>
            <Stack component="ol" spacing={0.5} sx={{ m: 0, pl: 3 }}>
              {item.because.map((step) => <Typography component="li" variant="body2" key={step}>{step}</Typography>)}
            </Stack>
          </Collapse>
        </>
      )}
    </Box>
  );
}

/**
 * The expert system's advice for the underwriter: what to check and ask, which terms pass the
 * policy, each with the chain of rules behind it. It advises; the decision stays with the person.
 */
export function AdviceBlock({ applicationId, version }: { applicationId: string; version: string }) {
  const advice = useApi(() => underwritingApi.advice(applicationId), [applicationId, version]);
  const [summary, setSummary] = useState<AssistantAnswer | null>(null);
  const [summarizing, setSummarizing] = useState(false);
  const [summaryError, setSummaryError] = useState<string | null>(null);

  const summarize = async () => {
    setSummarizing(true);
    setSummaryError(null);
    try {
      setSummary(await assistantApi.underwritingSummary(applicationId));
    } catch (error) {
      setSummaryError(error instanceof Error ? error.message : 'Не удалось подготовить сводку');
    } finally {
      setSummarizing(false);
    }
  };

  if (advice.loading || advice.error || !advice.data) return (
    <Paper variant="outlined" sx={{ p: { xs: 2, md: 3 } }}>
      <Typography variant="h6" component="h2">Рекомендации экспертной системы</Typography>
      {advice.loading ? <Typography role="status" sx={{ mt: 2 }}>Загружаем рекомендации…</Typography>
        : <Alert severity="warning" sx={{ mt: 2 }} action={<Button onClick={() => void advice.reload()}>Повторить</Button>}>
          {advice.error ?? 'Рекомендации пока недоступны'}. Основное досье доступно.
        </Alert>}
    </Paper>
  );
  const data = advice.data;
  return (
    <Paper variant="outlined" sx={{ p: { xs: 2, md: 3 } }}>
      <Stack direction="row" justifyContent="space-between" alignItems="baseline" spacing={2} flexWrap="wrap" useFlexGap>
        <Typography variant="h6" component="h2">Рекомендации экспертной системы</Typography>
        <Typography variant="body2" color="text.secondary">{data.engine} · правил сработало {data.rulesFired} из {data.rulesTotal}</Typography>
      </Stack>
      <Alert severity={data.items.some((item) => item.kind === 'STOP') ? 'error' : 'info'} sx={{ mt: 2 }}>{data.verdict}</Alert>
      <Box sx={{ mt: 1 }}>
        {data.items.map((item) => <Item key={item.code} item={item} />)}
      </Box>
      {data.items.length > 0 && (
        <Box sx={{ mt: 2 }}>
          {summary ? (
            <Box sx={{ p: 2, borderRadius: '16px', bgcolor: 'background.default' }}>
              <Typography variant="body2" color="text.secondary">Кратко · {summary.mode === 'MODEL' ? 'пересказ модели по выводам системы' : 'по правилам'}</Typography>
              <Typography sx={{ mt: 0.5, whiteSpace: 'pre-wrap' }}>{summary.text}</Typography>
            </Box>
          ) : (
            <Button variant="outlined" onClick={summarize} disabled={summarizing}>{summarizing ? 'Готовлю сводку…' : 'Кратко словами'}</Button>
          )}
          {summaryError && <Alert severity="warning" sx={{ mt: 1 }}>{summaryError}</Alert>}
        </Box>
      )}
      <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 2 }}>
        Система советует, но не решает: решение и его обоснование — за специалистом.
      </Typography>
    </Paper>
  );
}
