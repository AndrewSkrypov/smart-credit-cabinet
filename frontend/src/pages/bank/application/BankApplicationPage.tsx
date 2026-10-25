import { Alert, Box, Button, Chip, Grid, List, ListItem, ListItemText, Paper, Stack, Typography } from '@mui/material';
import ArrowBackRoundedIcon from '@mui/icons-material/ArrowBackRounded';
import { useCallback, useEffect, useRef, useState } from 'react';
import { Link as RouterLink, useParams, useLocation } from 'react-router-dom';
import { applicationsApi, underwritingApi } from '../../../api/client';
import { ErrorState } from '../../../components/ErrorState';
import { LoadingBlock } from '../../../components/LoadingBlock';
import { PageHeader } from '../../../components/PageHeader';
import { MessagesBlock } from '../../../components/MessagesBlock';
import { GeneratedFilesBlock } from '../../../components/GeneratedFilesBlock';
import { statusTitle, statusTone } from '../../../shared/applicationText';
import { fileSize, formatDateTime, months, percent, rub, rubExact } from '../../../shared/format';
import { Application, AssessmentView } from '../../../types';
import { AdviceBlock } from './AdviceBlock';
import { AuditBlock, DiscrepanciesBlock, ScoreBlock, SourcesBlock } from './AssessmentBlocks';
import { DecisionPanel, outcomeTitles } from './DecisionPanel';
import { BankSummary } from './BankSummary';

const REFRESH_MS = 10000;

const offerStatusTitles: Record<string, string> = {
  ISSUED: 'ждёт ответа клиента',
  ACCEPTED: 'принято',
  DECLINED: 'отклонено клиентом',
  EXPIRED: 'срок истёк',
  WITHDRAWN: 'отозвано'
};

export function BankApplicationPage() {
  const { id = '' } = useParams();
  return <BankApplicationCard key={id} id={id} />;
}

function BankApplicationCard({ id }: { id: string }) {
  const location = useLocation();
  const [application, setApplication] = useState<Application | null>(null);
  const [assessment, setAssessment] = useState<AssessmentView | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const mounted = useRef(false);
  const requestSequence = useRef(0);
  const inFlight = useRef(false);

  const load = useCallback(async () => {
    if (inFlight.current) return;
    inFlight.current = true;
    const sequence = ++requestSequence.current;
    try {
      const [nextApplication, nextAssessment] = await Promise.all([applicationsApi.get(id), underwritingApi.assessment(id)]);
      if (!mounted.current || sequence !== requestSequence.current) return;
      setApplication(nextApplication);
      setAssessment(nextAssessment);
      setError(null);
    } catch (nextError) {
      if (!mounted.current || sequence !== requestSequence.current) return;
      setError(nextError instanceof Error ? nextError.message : 'Не удалось загрузить заявку');
    } finally {
      inFlight.current = false;
    }
  }, [id]);

  useEffect(() => {
    mounted.current = true;
    void load();
    const timer = window.setInterval(() => void load(), REFRESH_MS);
    return () => { mounted.current = false; window.clearInterval(timer); };
  }, [load]);

  if (error && !application) return <ErrorState message={error} onRetry={load} />;
  if (!application || !assessment) return <LoadingBlock />;

  const tone = statusTone(application.status);
  const offer = application.process.offer;
  const facts: Array<[string, string]> = [
    ['Продукт', application.product.name],
    ['Цель', application.purposeTitle + (application.purposeComment ? ` — ${application.purposeComment}` : '')],
    ['Сумма и срок', `${rub(application.amount)} · ${months(application.termMonths)}`],
    ['Выручка за 12 мес. (со слов клиента)', application.declaredRevenue12m != null ? rub(application.declaredRevenue12m) : '—'],
    ['Платежи по кредитам (со слов клиента)', application.declaredMonthlyDebtPayments != null ? rub(application.declaredMonthlyDebtPayments) : '—'],
    ['Отправлена', application.submittedAt ? formatDateTime(application.submittedAt) : '—']
  ];

  return (
    <>
      <Button component={RouterLink} to={typeof location.state?.listUrl === 'string' && /^\/bank(?:\?|$)/.test(location.state.listUrl) ? location.state.listUrl : '/bank'} state={{ restoreList: true }} startIcon={<ArrowBackRoundedIcon />} sx={{ mb: 1 }}>Очередь</Button>
      <PageHeader
        title={`Заявка № ${application.number}`}
        subtitle={`${application.company.name ?? 'Компания'} · ИНН ${application.company.inn}`}
        actions={<Chip label={statusTitle(application.status)} color={tone === 'default' ? 'default' : tone} sx={{ alignSelf: { xs: 'flex-start', md: 'center' } }} />}
      />
      {notice && <Alert severity="success" sx={{ mb: 2 }} onClose={() => setNotice(null)}>{notice}</Alert>}
      {error && <Alert severity="warning" sx={{ mb: 2 }} action={<Button onClick={() => void load()}>Повторить</Button>}>{error}. Показаны последние полученные данные; действия появятся после обновления.</Alert>}
      <BankSummary application={application} assessment={assessment} stale={Boolean(error)} />
      <Grid container spacing={3}>
        <Grid item xs={12} lg={7}>
          <Stack spacing={3}>
            {!error && <DecisionPanel application={application} assessment={assessment} onDone={() => { setNotice('Решение сохранено, клиент увидит его в кабинете'); void load(); }} />}
            <AdviceBlock applicationId={application.id} version={`${application.status}-${application.statusChangedAt}`} />
            <ScoreBlock decision={assessment.automaticDecision} />
            {assessment.latestDecision && assessment.latestDecision.decidedByType !== 'SYSTEM' && (
              <Paper variant="outlined" sx={{ p: { xs: 2, md: 3 } }}>
                <Typography variant="h6" component="h2">Решение специалиста</Typography>
                <Typography>{outcomeTitles[assessment.latestDecision.outcome] ?? assessment.latestDecision.outcome} · {formatDateTime(assessment.latestDecision.createdAt)}</Typography>
                {assessment.latestDecision.comment && <Typography color="text.secondary">{assessment.latestDecision.comment}</Typography>}
              </Paper>
            )}
            <SourcesBlock assessment={assessment} />
            <MessagesBlock key={application.id} applicationId={application.id} status={application.status} />
          </Stack>
        </Grid>
        <Grid item xs={12} lg={5}>
          <Stack spacing={3}>
            <DiscrepanciesBlock assessment={assessment} />
            <Paper variant="outlined" sx={{ p: { xs: 2, md: 3 } }}>
              <Typography variant="h6" component="h2" gutterBottom>Заявка клиента</Typography>
              <Stack spacing={1}>
                {facts.map(([label, value]) => (
                  <Box key={label}>
                    <Typography variant="body2" color="text.secondary">{label}</Typography>
                    <Typography>{value}</Typography>
                  </Box>
                ))}
              </Stack>
            </Paper>
            {offer && (
              <Paper variant="outlined" sx={{ p: { xs: 2, md: 3 } }}>
                <Typography variant="h6" component="h2" gutterBottom>Предложение клиенту</Typography>
                <Typography>{offer.kind === 'COUNTER' ? 'Другие условия' : 'Запрошенные условия'} · {offer.productName}</Typography>
                <Typography>{rub(offer.amount)} · {months(offer.termMonths)} · {percent(offer.rate)} · первый платёж {rubExact(offer.monthlyPayment)}</Typography>
                <Typography variant="body2" color="text.secondary">{offerStatusTitles[offer.status] ?? offer.status} · действует до {formatDateTime(offer.validUntil)}</Typography>
              </Paper>
            )}
            {application.process.loan && (
              <Paper variant="outlined" sx={{ p: { xs: 2, md: 3 } }}>
                <Typography variant="h6" component="h2" gutterBottom>Кредит</Typography>
                <Typography>Договор {application.process.loan.contractNumber}</Typography>
                <Typography color="text.secondary">Ссудный счёт {application.process.loan.loanAccount}</Typography>
              </Paper>
            )}
            {(offer || application.process.loan) && <GeneratedFilesBlock key={application.id} applicationId={application.id} final={Boolean(application.process.loan)} />}
            <Paper variant="outlined" sx={{ p: { xs: 2, md: 3 } }}>
              <Typography variant="h6" component="h2">Документы</Typography>
              {application.documents.length === 0 ? (
                <Typography color="text.secondary">Клиент документов не загружал</Typography>
              ) : (
                <List dense disablePadding>
                  {application.documents.map((document) => (
                    <ListItem key={document.id} disableGutters>
                      <ListItemText
                        primary={
                          <Button variant="text" sx={{ p: 0, minHeight: 0 }} onClick={() => applicationsApi.downloadDocument(application.id, document.id, document.fileName)}>
                            {document.fileName}
                          </Button>
                        }
                        secondary={`${document.title} · ${fileSize(document.sizeBytes)} · ${formatDateTime(document.uploadedAt)}`}
                      />
                    </ListItem>
                  ))}
                </List>
              )}
            </Paper>
            <AuditBlock applicationId={application.id} version={`${application.status}-${application.statusChangedAt}`} />
          </Stack>
        </Grid>
      </Grid>
    </>
  );
}
