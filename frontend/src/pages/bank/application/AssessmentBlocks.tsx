import { Alert, Box, Button, Chip, Grid, List, ListItem, ListItemText, Paper, Stack, Table, TableBody, TableCell, TableHead, TableRow, Tooltip, Typography } from '@mui/material';
import WarningAmberRoundedIcon from '@mui/icons-material/WarningAmberRounded';
import { Fragment, useState } from 'react';
import { applicationsApi } from '../../../api/client';
import { HBar } from '../../../components/HBar';
import { TableScrollRegion } from '../../../components/TableScrollRegion';
import { useApi } from '../../../hooks/useApi';
import { eventText } from '../../../shared/applicationText';
import { formatDate, formatDateTime, rub } from '../../../shared/format';
import { tokens } from '../../../theme/theme';
import { AssessmentView, DecisionView } from '../../../types';

const actorTitles: Record<string, string> = { CLIENT: 'Клиент', SYSTEM: 'Система', MANAGER: 'Менеджер', UNDERWRITER: 'Андеррайтер' };

/** Score, rating and the points of every factor, with what the rules found decisive. */
export function ScoreBlock({ decision }: { decision: DecisionView | null }) {
  if (!decision) return null;
  return (
    <Paper variant="outlined" sx={{ p: { xs: 2, md: 3 } }}>
      <Stack direction="row" spacing={2} alignItems="baseline" justifyContent="space-between" flexWrap="wrap" useFlexGap>
        <Typography variant="h6" component="h2">Скоринг</Typography>
        <Typography variant="body2" color="text.secondary">правила v{decision.ruleSetVersion} · {formatDateTime(decision.createdAt)}</Typography>
      </Stack>
      {decision.score != null ? (
        <Stack direction="row" spacing={2} alignItems="center" sx={{ my: 2 }}>
          <Box sx={{ width: 56, height: 56, borderRadius: '16px', display: 'grid', placeItems: 'center', bgcolor: tokens.dark, color: '#fff' }}>
            <Typography variant="h5" component="span" fontWeight={700}>{decision.rating}</Typography>
          </Box>
          <Box>
            <Typography variant="h6" component="p">{decision.score} из 100 баллов</Typography>
            <Typography variant="body2" color="text.secondary">Границы рейтингов — по версии правил {decision.ruleSetVersion}</Typography>
          </Box>
        </Stack>
      ) : (
        <Alert severity="info" sx={{ my: 2 }}>Скоринг не проводился: не все источники ответили.</Alert>
      )}
      {decision.stopFactors.length > 0 && (
        <Alert severity="error" sx={{ mb: 2 }}>Стоп-факторы: {decision.stopFactors.map((item) => item.title).join('; ')}</Alert>
      )}
      {decision.factors.map((factor) => (
        <HBar key={factor.factor} label={factor.title} value={factor.points} max={factor.maxPoints} valueText={`${factor.points} / ${factor.maxPoints}`}
          detail={`${factor.value}${factor.hint ? ` — ${factor.hint}` : ''}`} />
      ))}
      {decision.factors.length > 0 && (
        <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>Наведите на фактор, чтобы увидеть значение из источника.</Typography>
      )}
    </Paper>
  );
}

/** Where the client's words and the sources disagree. */
export function DiscrepanciesBlock({ assessment }: { assessment: AssessmentView }) {
  if (assessment.discrepancies.length === 0) return null;
  return (
    <Paper variant="outlined" sx={{ p: { xs: 2, md: 3 }, borderColor: 'warning.main' }}>
      <Typography variant="h6" component="h2" gutterBottom>Расхождения</Typography>
      <Stack spacing={1}>
        {assessment.discrepancies.map((item) => (
          <Stack key={item.field} direction="row" spacing={1.5} alignItems="flex-start">
            <WarningAmberRoundedIcon color="warning" />
            <Typography>
              <b>{item.field}:</b> клиент указал {item.declared}, {item.source} — {item.actual}
              {item.differencePercent != null ? ` (расхождение ${item.differencePercent}%)` : ''}
            </Typography>
          </Stack>
        ))}
      </Stack>
    </Paper>
  );
}

function Facts({ rows }: { rows: Array<[string, React.ReactNode]> }) {
  return (
    <Box component="dl" sx={{ m: 0, display: 'grid', gridTemplateColumns: 'minmax(96px, 2fr) 3fr', columnGap: 2, rowGap: 1 }}>
      {rows.map(([label, value]) => (
        <Fragment key={label}>
          <Typography component="dt" variant="body2" color="text.secondary" sx={{ minWidth: 0, overflowWrap: 'anywhere' }}>{label}</Typography>
          <Typography component="dd" variant="body2" sx={{ m: 0, minWidth: 0, overflowWrap: 'anywhere' }}>{value}</Typography>
        </Fragment>
      ))}
    </Box>
  );
}

/** What each source answered, as it was when the bank asked. */
function SourceReceived({ at }: { at: string | undefined }) {
  return <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>{at ? `Получено: ${formatDateTime(at)}` : 'Дата получения не сохранена'}</Typography>;
}

export function SourcesBlock({ assessment }: { assessment: AssessmentView }) {
  const { registry, tax, bureau, bank } = assessment;
  const maxInflow = Math.max(1, ...(bank?.monthlyInflows ?? []));
  return (
    <Paper variant="outlined" sx={{ p: { xs: 2, md: 3 } }}>
      <Typography variant="h6" component="h2" gutterBottom>Данные источников</Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>«Получено» — время ответа в кабинете, а не дата актуальности сведений. Отчётный период указан отдельно.</Typography>
      <Grid container spacing={3}>
        <Grid item xs={12} md={6}>
          <Typography variant="subtitle1" fontWeight={700} gutterBottom>ЕГРЮЛ / ЕГРИП</Typography>
          {registry && <SourceReceived at={assessment.sourceFetchedAt?.REGISTRY} />}
          {registry ? (
            <Facts rows={[
              ['Наименование', registry.fullName || registry.shortName],
              ['Статус', <Chip key="s" size="small" label={registry.statusTitle} color={registry.status === 'ACTIVE' ? 'success' : 'error'}
                sx={{ height: 'auto', minHeight: 24, '& .MuiChip-label': { whiteSpace: 'normal', overflowWrap: 'anywhere', py: '1px' } }} />],
              ['ОГРН', registry.ogrn],
              ['Регистрация', formatDate(registry.registrationDate)],
              ['ОКВЭД', `${registry.okvedCode} ${registry.okvedName}`],
              ['Руководитель', registry.directorName],
              ['Адрес', registry.address],
              ['Недостоверные сведения', registry.unreliableAddress || registry.unreliableDirector ? 'есть' : 'нет']
            ]} />
          ) : <Typography color="text.secondary">Ещё не получены</Typography>}
        </Grid>
        <Grid item xs={12} md={6}>
          <Typography variant="subtitle1" fontWeight={700} gutterBottom>ФНС</Typography>
          {tax && <SourceReceived at={assessment.sourceFetchedAt?.TAX} />}
          {tax ? (
            tax.hasReports ? (
              <Facts rows={[
                ['Отчётный год', String(tax.reportingYear)],
                ['Выручка', tax.revenue != null ? rub(tax.revenue) : '—'],
                ['Расходы', tax.expenses != null ? rub(tax.expenses) : '—'],
                ['Недоимка', rub(tax.taxDebt)],
                ['Численность', String(tax.averageHeadcount ?? '—')],
                ['Режим', tax.taxRegime ?? '—']
              ]} />
            ) : <Typography color="text.secondary">Отчётности ещё нет</Typography>
          ) : <Typography color="text.secondary">Ещё не получены</Typography>}
        </Grid>
        <Grid item xs={12}>
          <Typography variant="subtitle1" fontWeight={700} gutterBottom>Бюро кредитных историй</Typography>
          {bureau && <SourceReceived at={assessment.sourceFetchedAt?.BUREAU} />}
          {bureau ? (
            <>
              <Facts rows={[
                ['Платежи в месяц', rub(bureau.monthlyPaymentsTotal)],
                ['Текущая просрочка', bureau.currentOverdueDaysMax > 0 ? `${bureau.currentOverdueDaysMax} дн.` : 'нет'],
                ['Худшая за 2 года', bureau.worstOverdueDays24m > 0 ? `${bureau.worstOverdueDays24m} дн.` : 'нет'],
                ['Запросов за 30 дней', String(bureau.inquiries30d)]
              ]} />
              {bureau.obligations.length > 0 && (
                <TableScrollRegion label="Все столбцы кредитных обязательств">
                  <Table size="small" aria-label="Обязательства">
                    <TableHead>
                      <TableRow>
                        <TableCell>Кредитор</TableCell>
                        <TableCell align="right">Лимит</TableCell>
                        <TableCell align="right">Остаток</TableCell>
                        <TableCell align="right">Платёж</TableCell>
                        <TableCell align="right">Просрочка</TableCell>
                      </TableRow>
                    </TableHead>
                    <TableBody>
                      {bureau.obligations.map((item) => (
                        <TableRow key={`${item.creditor}-${item.limit}`}>
                          <TableCell>{item.creditor}</TableCell>
                          <TableCell align="right">{rub(item.limit)}</TableCell>
                          <TableCell align="right">{rub(item.balance)}</TableCell>
                          <TableCell align="right">{rub(item.monthlyPayment)}</TableCell>
                          <TableCell align="right" sx={{ color: item.currentOverdueDays > 0 ? 'error.main' : undefined }}>{item.currentOverdueDays > 0 ? `${item.currentOverdueDays} дн.` : '—'}</TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                </TableScrollRegion>
              )}
            </>
          ) : <Typography color="text.secondary">Ещё не получены (или источник не отвечает)</Typography>}
        </Grid>
        <Grid item xs={12}>
          <Typography variant="subtitle1" fontWeight={700} gutterBottom>Обороты по счёту в банке</Typography>
          {bank && <SourceReceived at={assessment.sourceFetchedAt?.BANK} />}
          {bank && bank.client && bank.monthlyInflows.length > 0 ? (
            <>
              <Stack direction="row" spacing="2px" alignItems="flex-end" sx={{ height: 96 }} role="img"
                aria-label={`Поступления за 12 месяцев: ${bank.monthlyInflows.map((value) => rub(value)).join(', ')}`}>
                {bank.monthlyInflows.map((value, index) => (
                  <Tooltip key={index} title={`Месяц ${index + 1}: ${rub(value)}`} disableInteractive>
                    <Box sx={{ flex: 1, height: `${Math.max(4, (value / maxInflow) * 100)}%`, bgcolor: tokens.dark, borderRadius: '4px 4px 0 0' }} />
                  </Tooltip>
                ))}
              </Stack>
              <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
                Поступления за 12 месяцев, от старых к новым · колебания ±{Math.round((assessment.turnoverVariation ?? 0) * 100)}%
              </Typography>
            </>
          ) : <Typography color="text.secondary">{bank ? 'Счёта в банке нет — обороты неизвестны' : 'Ещё не получены'}</Typography>}
        </Grid>
      </Grid>
    </Paper>
  );
}

/** The journal keeps a source's raw answer for the audit; on screen only the words before it are useful. */
export function readableComment(comment: string | null): string | null {
  if (!comment) return null;
  const text = comment.replace(/\s*:?\s*"?[{[][\s\S]*$/, '').trim();
  return text.length > 0 ? text : null;
}

const JOURNAL_PREVIEW = 8;

/** Every event of the application with who caused it: the audit trail. */
export function AuditBlock({ applicationId, version }: { applicationId: string; version: string }) {
  const { data } = useApi(() => applicationsApi.events(applicationId), [applicationId, version]);
  const [expanded, setExpanded] = useState(false);
  if (!data) return null;
  const entries = [...data].reverse();
  const shown = expanded ? entries : entries.slice(0, JOURNAL_PREVIEW);
  return (
    <Paper variant="outlined" sx={{ p: { xs: 2, md: 3 } }}>
      <Typography variant="h6" component="h2">Журнал</Typography>
      <List dense>
        {shown.map((entry) => (
          <ListItem key={entry.id} disableGutters>
            <ListItemText
              primary={`${eventText(entry)}${entry.reasonCode ? ` · ${entry.reasonCode}` : ''}`}
              secondary={[formatDateTime(entry.occurredAt), actorTitles[entry.actorType], readableComment(entry.comment)].filter(Boolean).join(' · ')}
            />
          </ListItem>
        ))}
      </List>
      {entries.length > JOURNAL_PREVIEW && (
        <Button variant="outlined" size="small" onClick={() => setExpanded(!expanded)}>
          {expanded ? 'Свернуть' : `Показать все события (${entries.length})`}
        </Button>
      )}
    </Paper>
  );
}
