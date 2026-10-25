import { Box, Grid, Paper, Stack, Table, TableBody, TableCell, TableHead, TableRow, ToggleButton, ToggleButtonGroup, Typography } from '@mui/material';
import { useState } from 'react';
import { analyticsApi } from '../../api/client';
import { EmptyState } from '../../components/EmptyState';
import { ErrorState } from '../../components/ErrorState';
import { HBar } from '../../components/HBar';
import { LoadingBlock } from '../../components/LoadingBlock';
import { PageHeader } from '../../components/PageHeader';
import { TableScrollRegion } from '../../components/TableScrollRegion';
import { useApi } from '../../hooks/useApi';
import { statusTitle } from '../../shared/applicationText';
import { formatMinutes } from '../../shared/duration';
import { rub } from '../../shared/format';
import { ApplicationStatus } from '../../types';
import { outcomeTitles } from './application/DecisionPanel';

const PERIODS = [7, 30, 90];

function Tile({ label, value, note }: { label: string; value: string; note?: string }) {
  return (
    <Paper variant="outlined" sx={{ p: { xs: 2, md: 3 }, height: '100%', overflowWrap: 'anywhere' }}>
      <Typography variant="body2" color="text.secondary">{label}</Typography>
      <Typography variant="h5" component="p" sx={{ mt: 0.5, fontSize: { xs: '1.375rem', md: '1.625rem' }, overflowWrap: 'anywhere' }}>{value.replace(/\u00a0/g, ' ')}</Typography>
      {note && <Typography variant="body2" color="text.secondary">{note}</Typography>}
    </Paper>
  );
}

const share = (part: number, whole: number) => (whole > 0 ? `${Math.round((part / whole) * 100)}%` : '—');

/** The funnel of the pipeline, from the journal of the applications themselves. */
export function AnalyticsPage() {
  const [days, setDays] = useState(30);
  const { data, loading, error, reload } = useApi(() => {
    const to = new Date();
    const from = new Date(to.getTime() - days * 24 * 3600 * 1000);
    return analyticsApi.funnel(from.toISOString(), to.toISOString());
  }, [days]);

  const header = (
    <PageHeader
      title="Аналитика конвейера"
      subtitle="Считается по журналу заявок: сколько дошло до каждого этапа и сколько времени заняло"
      actions={
        <ToggleButtonGroup exclusive size="small" value={days} onChange={(_, value: number | null) => value && setDays(value)} aria-label="Период">
          {PERIODS.map((period) => <ToggleButton key={period} value={period} sx={{ px: { xs: 1, md: 2 }, minHeight: 44, minWidth: 0, flex: { xs: 1, md: '0 1 auto' } }}>{period} дней</ToggleButton>)}
        </ToggleButtonGroup>
      }
    />
  );
  if (loading && !data) return <>{header}<LoadingBlock /></>;
  if (error || !data) return <>{header}<ErrorState message={error ?? 'Нет данных'} onRetry={reload} /></>;

  const sent = data.funnel[0]?.applications ?? 0;
  const automatic = data.automaticOutcomes.reduce((sum, item) => sum + item.decisions, 0);
  const automaticFinal = data.automaticOutcomes.filter((item) => item.outcome !== 'MANUAL_REVIEW').reduce((sum, item) => sum + item.decisions, 0);
  const manual = data.manualOutcomes.filter((item) => item.outcome !== 'REQUEST_INFO').reduce((sum, item) => sum + item.decisions, 0);
  const longestMedian = Math.max(1, ...data.stageTimes.map((item) => item.medianMinutes ?? 0));
  const statuses = Object.entries(data.currentStatuses).sort((a, b) => b[1] - a[1]);

  return (
    <>
      {header}
      {sent === 0 ? (
        <EmptyState title="За период заявок не было" description="Выберите период побольше или дождитесь заявок." />
      ) : (
        <Stack spacing={3}>
          <Box><Grid container spacing={2}>
            <Grid item xs={6} md={3}><Tile label="Отправлено заявок" value={String(sent)} /></Grid>
            <Grid item xs={6} md={3}><Tile label="Выдано" value={rub(data.disbursedAmount)} note={`${data.funnel.at(-1)?.applications ?? 0} кредитов`} /></Grid>
            <Grid item xs={6} md={3}><Tile label="Решено автоматически" value={share(automaticFinal, automatic)} note={`${automaticFinal} из ${automatic} оценок`} /></Grid>
            <Grid item xs={6} md={3}><Tile label="Решения вопреки рекомендации" value={String(data.manualOverrides)} note={`из ${manual} ручных решений`} /></Grid>
          </Grid></Box>

          <Paper variant="outlined" sx={{ p: { xs: 2, md: 3 } }}>
            <Typography variant="h6" component="h2" gutterBottom>Воронка</Typography>
            {data.funnel.map((step) => (
              <HBar key={step.code} label={step.title} value={step.applications} max={sent}
                valueText={`${step.applications} · ${share(step.applications, sent)}`} detail={`${step.title}: ${step.applications} из ${sent} отправленных заявок`} />
            ))}
          </Paper>

          <Box><Grid container spacing={3}>
            <Grid item xs={12} lg={7}>
              <Paper variant="outlined" sx={{ p: { xs: 2, md: 3 }, height: '100%' }}>
                <Typography variant="h6" component="h2">Время на этапах</Typography>
                <Typography variant="body2" color="text.secondary" gutterBottom>Медиана: половина заявок проходит этап быстрее</Typography>
                {data.stageTimes.length === 0 ? <Typography color="text.secondary">Пока нет завершённых этапов</Typography> : (
                  <>
                    {data.stageTimes.map((stage) => (
                      <HBar key={stage.status} label={stage.title} value={stage.medianMinutes ?? 0} max={longestMedian}
                        valueText={formatMinutes(stage.medianMinutes)} detail={`Медиана ${formatMinutes(stage.medianMinutes)}, среднее ${formatMinutes(stage.averageMinutes)}, переходов ${stage.transitions}`} />
                    ))}
                    <TableScrollRegion label="Все столбцы времени на этапах">
                      <Table size="small" aria-label="Время на этапах, таблица">
                        <TableHead>
                          <TableRow>
                            <TableCell>Этап</TableCell>
                            <TableCell align="right">Медиана</TableCell>
                            <TableCell align="right">Среднее</TableCell>
                            <TableCell align="right">Переходов</TableCell>
                          </TableRow>
                        </TableHead>
                        <TableBody>
                          {data.stageTimes.map((stage) => (
                            <TableRow key={stage.status}>
                              <TableCell>{stage.title}</TableCell>
                              <TableCell align="right">{formatMinutes(stage.medianMinutes)}</TableCell>
                              <TableCell align="right">{formatMinutes(stage.averageMinutes)}</TableCell>
                              <TableCell align="right">{stage.transitions}</TableCell>
                            </TableRow>
                          ))}
                        </TableBody>
                      </Table>
                    </TableScrollRegion>
                  </>
                )}
              </Paper>
            </Grid>
            <Grid item xs={12} lg={5}>
              <Stack spacing={3}>
                <Paper variant="outlined" sx={{ p: { xs: 2, md: 3 } }}>
                  <Typography variant="h6" component="h2" gutterBottom>Автоматические решения</Typography>
                  {data.automaticOutcomes.map((item) => (
                    <HBar key={item.outcome} label={outcomeTitles[item.outcome] ?? item.outcome} value={item.decisions} max={automatic} valueText={`${item.decisions} · ${share(item.decisions, automatic)}`} />
                  ))}
                </Paper>
                <Paper variant="outlined" sx={{ p: { xs: 2, md: 3 } }}>
                  <Typography variant="h6" component="h2" gutterBottom>Распределение заявок по статусам</Typography>
                  {statuses.map(([status, count]) => (
                    <Stack key={status} direction="row" justifyContent="space-between" sx={{ py: 0.5 }}>
                      <Typography>{statusTitle(status as ApplicationStatus)}</Typography>
                      <Typography fontWeight={500}>{count}</Typography>
                    </Stack>
                  ))}
                </Paper>
              </Stack>
            </Grid>
          </Grid></Box>
        </Stack>
      )}
    </>
  );
}
