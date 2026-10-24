import { Paper, Stack, Typography } from '@mui/material';
import { Application, AssessmentView } from '../../../types';
import { statusTitle } from '../../../shared/applicationText';
import { formatDateTime, months, rub } from '../../../shared/format';
import { outcomeTitles } from './DecisionPanel';

const decisions: Record<string, string> = {
  APPROVE: 'Одобрено на запрошенных условиях', COUNTER_OFFER: 'Предложены другие условия',
  REJECT: 'Отказ', REQUEST_INFO: 'Запрошены данные', MANUAL_REVIEW: 'Передано на ручное рассмотрение'
};

/** Compact index of existing bank facts, without an additional score or inferred verdict. */
export function BankSummary({ application, assessment, stale }: { application: Application; assessment: AssessmentView; stale: boolean }) {
  const latest = assessment.latestDecision;
  const reasons = latest ? [...latest.stopFactors, ...latest.manualReasons].map((reason) => reason.title) : [];
  const explanation = reasons.length ? [...new Set(reasons)].join('; ') : latest?.comment || application.process.outcome?.explanation.join('; ');
  const missing = ([['registry', 'ЕГРЮЛ / ЕГРИП'], ['tax', 'ФНС'], ['bureau', 'БКИ'], ['bank', 'Банк']] as const)
    .filter(([source]) => !assessment[source]).map(([, title]) => title);
  const unknown = [missing.length ? `Не получены ответы: ${missing.join(', ')}.` : '',
    assessment.tax && (!assessment.tax.hasReports || assessment.tax.revenue == null) ? 'Выручка ФНС неизвестна.' : '',
    assessment.bank && !assessment.bank.client ? 'Обороты по счёту в банке неизвестны: у компании нет счёта в этом банке.' : '']
    .filter(Boolean).join(' ');
  const next = stale ? 'Дождитесь обновления данных или нажмите «Повторить».'
    : assessment.actions.length ? assessment.actions.map((action) => outcomeTitles[action]).join(' · ')
      : application.status === 'INFO_REQUESTED' ? 'Ожидаются данные клиента.'
        : ['OFFER_ISSUED', 'SIGNING'].includes(application.status) ? 'Ожидается ответ или подпись клиента.'
          : ['ENRICHMENT', 'SCORING', 'DISBURSING', 'SUBMITTED'].includes(application.status) ? 'Идёт автоматическая обработка.'
            : 'Для вашей роли действий по рассмотрению сейчас нет.';
  return <Paper variant="outlined" sx={{ p: { xs: 2, md: 3 }, mb: 3 }}>
    <Typography variant="h6" component="h2" gutterBottom>Краткий итог</Typography>
    <Stack spacing={1}>
      <Typography><b>Запрос:</b> {application.product.name} · {rub(application.amount)} · {months(application.termMonths)} · {application.purposeTitle}.</Typography>
      <Typography><b>Сейчас:</b> {statusTitle(application.status)}.</Typography>
      <Typography><b>Последнее решение:</b> {latest ? `${decisions[latest.outcome] ?? latest.outcome} · ${latest.decidedByType === 'SYSTEM' ? 'автоматически' : 'специалистом'} · ${formatDateTime(latest.createdAt)}` : 'ещё нет'}.</Typography>
      {explanation && <Typography><b>Основание:</b> {explanation}</Typography>}
      {unknown && <Typography color="text.secondary"><b>Неизвестно:</b> {unknown}</Typography>}
      <Typography><b>{assessment.actions.length && !stale ? 'Доступно вам' : 'Следующий шаг'}:</b> {next}</Typography>
    </Stack>
  </Paper>;
}
