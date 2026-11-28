import {
  Alert, Box, Button, Chip, MenuItem, Paper, Stack, TextField, ToggleButton, ToggleButtonGroup,
  Typography, useMediaQuery, useTheme,
} from '@mui/material';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { applicationsApi, loanServicingApi } from '../../api/client';
import { ErrorState } from '../../components/ErrorState';
import { LoadingBlock } from '../../components/LoadingBlock';
import { useApi } from '../../hooks/useApi';
import { statusTitle, statusTone } from '../../shared/applicationText';
import { rub, rubExact } from '../../shared/format';
import { ApplicationSummary } from '../../types';
import { CreditEvent, CreditEventKind, creditTimeline } from './creditTimelineEvents';

const DAY = 86_400_000;
const timeZone = 'Europe/Moscow';
const calendarDate = new Intl.DateTimeFormat('en-CA', { timeZone, year: 'numeric', month: '2-digit', day: '2-digit' });
const shortDate = new Intl.DateTimeFormat('ru-RU', { timeZone, month: 'short', day: 'numeric' });
const fullDate = new Intl.DateTimeFormat('ru-RU', { timeZone, year: 'numeric', month: 'long', day: 'numeric' });
const timeOfDay = new Intl.DateTimeFormat('ru-RU', { timeZone, hour: '2-digit', minute: '2-digit' });
const shortMoney = new Intl.NumberFormat('ru-RU', { notation: 'compact', maximumFractionDigits: 1 });
const labels: Record<CreditEventKind, string> = {
  status: 'Статус заявки',
  deadline: 'Установленный срок',
  income: 'Выдача или транш',
  paid: 'Внесённый платёж',
  scheduled: 'Платёж по графику',
};

async function loadTimeline(application: ApplicationSummary) {
  const [details, history] = await Promise.allSettled([
    applicationsApi.get(application.id),
    applicationsApi.events(application.id),
  ]);
  const current = details.status === 'fulfilled' ? details.value : null;
  let account = null;
  let missingSchedule = false;
  if ((current?.status ?? application.status) === 'DISBURSED' || current?.process.loan) {
    try {
      account = await loanServicingApi.getByApplication(application.id);
    } catch {
      missingSchedule = true;
    }
  }
  return {
    events: creditTimeline(application, history.status === 'fulfilled' ? history.value : [], current, account),
    status: current?.status ?? application.status,
    amount: current?.amount ?? application.amount,
    isPartial: details.status === 'rejected' || history.status === 'rejected' || missingSchedule,
  };
}

function hasAmount(event: CreditEvent) {
  return (event.kind === 'income' || event.kind === 'paid' || event.kind === 'scheduled')
    && Number.isFinite(event.amount) && (event.amount ?? 0) > 0;
}

function CreditChart({ events }: { events: CreditEvent[] }) {
  const theme = useTheme();
  const wide = useMediaQuery(theme.breakpoints.up('sm'));
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [range, setRange] = useState<'all' | 'upcoming'>('all');
  const today = Date.now();
  const { year, month, day } = Object.fromEntries(calendarDate.formatToParts(today).map(({ type, value }) => [type, value]));
  const startOfToday = Date.parse(`${year}-${month}-${day}T00:00:00+03:00`);
  const endOfWindow = startOfToday + 90 * DAY;
  const visible = range === 'all' ? events : events.filter((event) => event.time >= startOfToday && event.time < endOfWindow);
  const selectedIndex = visible.findIndex((event) => event.id === selectedId);
  const firstUpcoming = visible.findIndex((event) => event.time >= startOfToday);
  const index = selectedIndex >= 0 ? selectedIndex : firstUpcoming >= 0 ? firstUpcoming : visible.length - 1;
  const selected = visible[index];
  const choose = (offset: number) => {
    const next = visible[index + offset];
    if (next) setSelectedId(next.id);
  };
  const colors: Record<CreditEventKind, string> = {
    status: theme.palette.text.primary,
    deadline: theme.palette.warning.dark,
    income: theme.palette.success.dark,
    paid: theme.palette.info.dark,
    scheduled: theme.palette.primary.main,
  };
  const financial = visible.filter(hasAmount);
  const maximum = financial.reduce((top, event) => Math.max(top, event.amount ?? 0), 0);
  const first = range === 'all' ? (visible[0]?.time ?? today) : startOfToday;
  const last = range === 'all' ? Math.max(first + DAY, visible.at(-1)?.time ?? 0) : endOfWindow;
  const width = wide ? 720 : 360;
  const xStart = maximum > 0 ? 64 : 24;
  const xEnd = width - 28;
  const base = maximum > 0 ? 196 : 56;
  const x = (time: number) => xStart + 8 + (time - first) / (last - first) * (xEnd - xStart - 16);
  const y = (event: CreditEvent) => hasAmount(event) ? base - ((event.amount ?? 0) / maximum) * 132 : 28;
  const ticks = wide ? [0, 0.25, 0.5, 0.75, 1] : [0, 0.5, 1];
  const payments = financial.filter(({ kind }) => kind !== 'income');
  const kinds = [...new Set(visible.map(({ kind }) => kind))];

  return (
    <Stack spacing={2}>
      <ToggleButtonGroup value={range} exclusive size="small" aria-label="Период графика"
        onChange={(_, value: 'all' | 'upcoming' | null) => { if (value) { setRange(value); setSelectedId(null); } }}>
        <ToggleButton value="all" sx={{ minHeight: 44 }}>Весь срок</ToggleButton>
        <ToggleButton value="upcoming" sx={{ minHeight: 44 }}>Ближайшие 90 дней</ToggleButton>
      </ToggleButtonGroup>
      {selected ? (
        <>
          <Box
            role="group"
            aria-label="График кредитных событий. Стрелки влево и вправо переключают события"
            tabIndex={0}
            onKeyDown={(event) => {
              if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return;
              event.preventDefault();
              choose(event.key === 'ArrowLeft' ? -1 : 1);
            }}
            sx={{ bgcolor: 'background.default', borderRadius: 2, border: '1px solid', borderColor: 'divider', p: 1 }}
          >
            <svg viewBox={`0 0 ${width} ${base + 34}`} width="100%" aria-hidden="true" style={{ display: 'block' }}>
              <g stroke={theme.palette.divider}>
                <line x1={xStart} x2={xEnd} y1={28} y2={28} />
                {maximum > 0 && [0, 0.5, 1].map((fraction) => (
                  <line key={fraction} x1={xStart} x2={xEnd} y1={base - fraction * 132} y2={base - fraction * 132} />
                ))}
              </g>
              {maximum > 0 && (
                <g fill={theme.palette.text.secondary} fontSize="12" textAnchor="end">
                  <text x={xStart - 6} y="48">Сумма, ₽</text>
                  {[0, 0.5, 1].map((fraction) => (
                    <text key={fraction} x={xStart - 6} y={base - fraction * 132 + 4}>
                      {shortMoney.format(maximum * fraction)}
                    </text>
                  ))}
                </g>
              )}
              <line x1={x(selected.time)} x2={x(selected.time)} y1="12" y2={base}
                stroke={theme.palette.text.secondary} strokeOpacity={0.5} strokeDasharray="3 4" />
              {payments.slice(1).map((payment, offset) => {
                const preceding = payments[offset];
                const planned = payment.kind === 'scheduled' || preceding.kind === 'scheduled';
                return (
                  <line key={payment.id} x1={x(preceding.time)} x2={x(payment.time)} y1={y(preceding)} y2={y(payment)}
                    stroke={planned ? colors.scheduled : colors.paid} strokeWidth="2" strokeDasharray={planned ? '5 4' : undefined} />
                );
              })}
              {visible.map((event) => (
                <g key={event.id} onPointerEnter={() => setSelectedId(event.id)} onClick={() => setSelectedId(event.id)}
                  style={{ cursor: 'pointer' }}>
                  {event.kind === 'income' && (
                    <line x1={x(event.time)} x2={x(event.time)} y1={y(event)} y2={base}
                      stroke={colors.income} strokeWidth="2" opacity="0.55" />
                  )}
                  <circle cx={x(event.time)} cy={y(event)} r="12" fill="transparent" />
                  <circle cx={x(event.time)} cy={y(event)} r={selected.id === event.id ? 7 : 4.5}
                    stroke={colors[event.kind]} strokeWidth="2"
                    fill={event.kind === 'deadline' || event.kind === 'scheduled' ? theme.palette.background.paper : colors[event.kind]} />
                </g>
              ))}
              <g fill={theme.palette.text.secondary} fontSize="12" textAnchor="middle">
                {ticks.map((fraction) => (
                  <text key={fraction} x={x(first + (last - first) * fraction)} y={base + 22}>
                    {shortDate.format(first + (last - first) * fraction)}
                  </text>
                ))}
              </g>
            </svg>
          </Box>
          <Stack direction="row" flexWrap="wrap" gap={1.5}>
            {kinds.map((kind) => (
              <Stack key={kind} direction="row" spacing={0.75} alignItems="center">
                <Box component="span" sx={{ width: 8, height: 8, borderRadius: '50%', bgcolor: colors[kind] }} />
                <Typography variant="caption">{labels[kind]}</Typography>
              </Stack>
            ))}
          </Stack>
          <Box aria-live="polite" sx={{ borderLeft: '3px solid', borderColor: colors[selected.kind], pl: 2, overflowWrap: 'anywhere' }}>
            <Typography variant="body2" color="text.secondary">
              {fullDate.format(selected.time)}{selected.date.includes('T') ? `, ${timeOfDay.format(selected.time)} МСК` : ''}
              {' · '}{labels[selected.kind]}
            </Typography>
            <Typography fontWeight={600}>
              {selected.title}
              {selected.amount !== undefined && (
                <Box component="span" sx={{ display: 'inline-block', ml: 0.5, whiteSpace: 'nowrap' }}>
                  · {rubExact(selected.amount)}
                </Box>
              )}
            </Typography>
          </Box>
          <Stack direction="row" flexWrap="wrap" gap={1} alignItems="center">
            <Button size="small" variant="outlined" disabled={index <= 0} onClick={() => choose(-1)} sx={{ minHeight: 44 }}>Раньше</Button>
            <Button size="small" variant="outlined" disabled={index === visible.length - 1} onClick={() => choose(1)} sx={{ minHeight: 44 }}>Позже</Button>
            <Typography variant="caption" color="text.secondary">{index + 1} из {visible.length}</Typography>
          </Stack>
        </>
      ) : <Typography color="text.secondary">На выбранный период событий нет.</Typography>}
    </Stack>
  );
}

function ApplicationTimeline({ application }: { application: ApplicationSummary }) {
  const { data, loading, error, reload } = useApi(() => loadTimeline(application), [application.id]);
  if (loading) return <LoadingBlock />;
  if (error || !data) return <ErrorState message={error ?? 'Не удалось загрузить кредитные события'} onRetry={reload} />;
  return (
    <Stack spacing={2}>
      <Stack direction="row" flexWrap="wrap" alignItems="center" gap={1}>
        <Chip size="small" color={statusTone(data.status)} label={statusTitle(data.status)} />
        <Typography variant="body2" color="text.secondary">Сумма заявки · {rub(data.amount)}</Typography>
      </Stack>
      {data.isPartial && (
        <Alert severity="warning" action={<Button color="inherit" onClick={() => void reload()}>Обновить</Button>}>
          Часть кредитных данных не загрузилась.
        </Alert>
      )}
      <CreditChart events={data.events} />
      <Button component={Link} to={`/client/applications/${application.id}`} sx={{ alignSelf: 'flex-start' }}>Открыть заявку</Button>
    </Stack>
  );
}

export function CreditTimeline() {
  const { data, loading, error, reload } = useApi(applicationsApi.list);
  const [selectedApplicationId, setSelectedApplicationId] = useState<string | null>(null);
  const selected = data?.find((item) => item.id === selectedApplicationId) ?? data?.[0];
  return (
    <Paper component="section" aria-labelledby="credit-timeline-title" sx={{ p: { xs: 2, sm: 3, md: 4 } }}>
      <Stack spacing={2}>
        <Box>
          <Typography id="credit-timeline-title" variant="h6" component="h2">Кредитный календарь</Typography>
          <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>
            Здесь видны изменения статуса, сроки действий, выдачи и платежи.
            Пунктир показывает платежи по договорному графику. Дата решения банка появится, когда банк его примет.
          </Typography>
        </Box>
        {loading ? <LoadingBlock /> : error ? <ErrorState message={error} onRetry={reload} /> : selected ? (
          <>
            <TextField select fullWidth size="small" label="Кредитная заявка" value={selected.id}
              onChange={(event) => setSelectedApplicationId(event.target.value)} sx={{ maxWidth: 520 }}>
              {data?.map((item) => (
                <MenuItem key={item.id} value={item.id} sx={{ whiteSpace: 'normal' }}>
                  {item.number !== null ? `№ ${item.number} · ` : ''}{item.productName} · {rub(item.amount)}
                </MenuItem>
              ))}
            </TextField>
            <ApplicationTimeline key={selected.id} application={selected} />
          </>
        ) : <Typography color="text.secondary">Здесь появятся события, когда у компании будет кредитная заявка.</Typography>}
      </Stack>
    </Paper>
  );
}
