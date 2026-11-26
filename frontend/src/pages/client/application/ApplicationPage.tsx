import { Alert, Box, Button, Checkbox, Chip, Dialog, DialogActions, DialogContent, DialogTitle, Divider, FormControlLabel, Grid, Paper, Stack, TextField, Typography } from '@mui/material';
import ArrowBackRoundedIcon from '@mui/icons-material/ArrowBackRounded';
import { useCallback, useEffect, useRef, useState } from 'react';
import { Link as RouterLink, useParams, useLocation } from 'react-router-dom';
import { ApiError, applicationsApi } from '../../../api/client';
import { financingApi } from '../../../api/financing';
import { FinancingScenarioView } from '../../../components/FinancingScenarioView';
import { useApi } from '../../../hooks/useApi';
import { ErrorState } from '../../../components/ErrorState';
import { LoadingBlock } from '../../../components/LoadingBlock';
import { PageHeader } from '../../../components/PageHeader';
import { MessagesBlock } from '../../../components/MessagesBlock';
import { explainStatus, SYSTEM_STATUSES, statusTitle, statusTone } from '../../../shared/applicationText';
import { formatDateTime, months, percent, rub, rubExact } from '../../../shared/format';
import { Application } from '../../../types';
import { DocumentsBlock } from './DocumentsBlock';
import { DraftForm } from './DraftForm';
import { HistoryBlock } from './HistoryBlock';
import { InfoRequestBlock } from './InfoRequestBlock';
import { LoanBlock } from './LoanBlock';
import { OfferBlock } from './OfferBlock';
import { GeneratedFilesBlock } from '../../../components/GeneratedFilesBlock';
import { LoanServicingBlock } from '../../../components/LoanServicingBlock';
import { SigningBlock } from './SigningBlock';
import { StageStepper } from './StageStepper';

function Fact({ label, value }: { label: string; value: string }) {
  return (
    <Box>
      <Typography variant="body2" color="text.secondary">{label}</Typography>
      <Typography fontWeight={500}>{value}</Typography>
    </Box>
  );
}

/** While the bank works the card refreshes every 2 seconds; otherwise every 15, to catch an underwriter's decision. */
const FAST_REFRESH_MS = 2000;
const SLOW_REFRESH_MS = 15000;

export function ApplicationPage() {
  const { id = '' } = useParams();
  return <ApplicationCard key={id} id={id} />;
}

function ApplicationCard({ id }: { id: string }) {
  const location = useLocation();
  const [application, setApplication] = useState<Application | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [withdrawing, setWithdrawing] = useState(false);
  const [withdrawComment, setWithdrawComment] = useState('');
  const [confirmConsent, setConfirmConsent] = useState(false);
  const [confirmBusy, setConfirmBusy] = useState(false);
  const [returning, setReturning] = useState(false);
  const [returnComment, setReturnComment] = useState('');
  const mounted = useRef(false);
  const requestSequence = useRef(0);
  const inFlight = useRef(false);

  const apply = useCallback((next: Application) => {
    if (!mounted.current || next.id !== id) return;
    // An action response is newer than a poll that started before the action completed.
    requestSequence.current += 1;
    setApplication(next);
    setError(null);
  }, [id]);

  const load = useCallback(async () => {
    if (!mounted.current || inFlight.current) return;
    inFlight.current = true;
    const sequence = ++requestSequence.current;
    try {
      const next = await applicationsApi.get(id);
      if (!mounted.current || sequence !== requestSequence.current || next.id !== id) return;
      setApplication(next);
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
    return () => { mounted.current = false; };
  }, [load]);

  const scenario = useApi(
    () => application?.agentId ? financingApi.client(id) : Promise.resolve(null),
    [id, application?.agentId, application?.status, application?.version]
  );
  const scenarioRevision = scenario.data?.revision;
  useEffect(() => setConfirmConsent(false), [scenarioRevision]);

  const status = application?.status;
  useEffect(() => {
    if (!status || (status === 'DRAFT' && !application?.agentId)) return;
    const timer = window.setInterval(() => void load(), SYSTEM_STATUSES.includes(status) ? FAST_REFRESH_MS : SLOW_REFRESH_MS);
    return () => window.clearInterval(timer);
  }, [status, application?.agentId, load]);

  if (error && !application) return <ErrorState message={error} onRetry={load} />;
  if (!application) return <LoadingBlock />;

  const explanation = explainStatus(application);
  const tone = statusTone(application.status);
  const outcome = application.process.outcome;
  const canResume = application.canResumeIndependently === true;
  const canWithdraw = application.allowedActions.includes('WITHDRAW');

  return (
    <>
      <Button component={RouterLink} to={typeof location.state?.listUrl === 'string' && /^\/client(?:\?|$)/.test(location.state.listUrl) ? location.state.listUrl : '/client'} state={{ restoreList: true }} startIcon={<ArrowBackRoundedIcon />} sx={{ mb: 1 }}>Все заявки</Button>
      <PageHeader
        title={`Заявка${application.number ? ` № ${application.number}` : ''}`}
        subtitle={`${application.product.name} · ${rub(application.amount)} · ${months(application.termMonths)}`}
        actions={<Chip label={statusTitle(application.status)} color={tone === 'default' ? 'default' : tone} sx={{ alignSelf: { xs: 'flex-start', md: 'center' } }} />}
      />
      <Grid container spacing={3} sx={{ pb: 6 }}>
        <Grid item xs={12} md={8}>
          <Stack spacing={3}>
            <Paper variant="outlined" sx={{ p: { xs: 2, md: 3 } }}>
              <StageStepper status={application.status} />
              <Box sx={{ mt: 2 }} aria-live="polite">
                <Typography fontWeight={600}>Что сейчас происходит</Typography>
                <Typography color="text.secondary">{explanation.now}</Typography>
                {explanation.action && (
                  <>
                    <Typography fontWeight={600} sx={{ mt: 1.5 }}>Что нужно от вас</Typography>
                    <Typography>{explanation.action}</Typography>
                  </>
                )}
              </Box>
            </Paper>

            {error && <Alert severity="warning">{error}</Alert>}

            {application.status === 'DRAFT' && application.editable && !canResume && (
              <DraftForm application={application} onSaved={apply} onSubmitted={apply} />
            )}
            {scenario.data && !scenario.error && <FinancingScenarioView applicationId={id} scenario={scenario.data} />}
            {application.agentId && scenario.error && (
              <Alert severity="warning" action={<Button onClick={() => void scenario.reload()}>Повторить</Button>}>{scenario.error}</Alert>
            )}
            {(application.status === 'PENDING_CLIENT_CONFIRMATION' || canResume) && (
              <Paper variant="outlined" sx={{ p: { xs: 2, md: 3 }, borderColor: 'warning.main' }}>
                <Typography variant="h6" component="h2" gutterBottom>Подтверждение агентской заявки</Typography>
                <Typography color="text.secondary" sx={{ mb: 2 }}>
                  {canResume ? 'Доступ агента завершён. Проверьте последний переданный пакет и продолжите по этой заявке.'
                    : application.agentName
                    ? `Агент ${application.agentName} подготовил заявку для вашей компании. Проверьте условия и приложенные документы перед отправкой в банк.`
                    : 'Агент подготовил заявку для вашей компании. Проверьте условия и приложенные документы перед отправкой в банк.'}
                </Typography>

                <Grid container spacing={2} sx={{ mb: 2 }}>
                  <Grid item xs={6} sm={4}><Fact label="Продукт" value={application.product.name} /></Grid>
                  <Grid item xs={6} sm={4}><Fact label="Сумма" value={rub(application.amount)} /></Grid>
                  <Grid item xs={6} sm={4}><Fact label="Срок" value={months(application.termMonths)} /></Grid>
                  <Grid item xs={12} sm={6}><Fact label="Цель" value={application.purposeTitle} /></Grid>
                  {application.declaredRevenue12m != null && (
                    <Grid item xs={6} sm={3}><Fact label="Выручка за 12 мес." value={rub(application.declaredRevenue12m)} /></Grid>
                  )}
                  <Grid item xs={6} sm={3}><Fact label="Документов приложено" value={String(application.documents.length)} /></Grid>
                </Grid>

                {application.documents.length > 0 && (
                  <Box sx={{ mb: 2 }}>
                    <Typography variant="body2" color="text.secondary">Приложенные файлы:</Typography>
                    <Typography variant="body2">{application.documents.map((doc) => doc.fileName).join(', ')}</Typography>
                  </Box>
                )}

                {application.currentProduct && application.currentProduct.version !== application.product.version && (
                  <Alert severity="info" sx={{ mb: 2 }}>
                    Условия продукта «{application.currentProduct.name}» обновились (версия {application.currentProduct.version}, базовая ставка {percent(application.currentProduct.baseRate)}). Подтверждая заявку, вы соглашаетесь с актуальными условиями продукта.
                  </Alert>
                )}

                <FormControlLabel
                  control={
                    <Checkbox
                      checked={confirmConsent}
                      disabled={confirmBusy}
                      onChange={(event) => setConfirmConsent(event.target.checked)}
                    />
                  }
                  label="Согласен на запрос кредитной истории компании в бюро кредитных историй (БКИ) и подтверждаю параметры заявки"
                />

                <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5} sx={{ mt: 2 }}>
                  {application.allowedActions.includes('CONFIRM_AND_SUBMIT') && (
                    <Button
                      variant="contained"
                      size="large"
                      disabled={!confirmConsent || confirmBusy || scenario.loading || !!scenario.error}
                      onClick={async () => {
                        setConfirmBusy(true);
                        setError(null);
                        try {
                          const productVersion = (application.currentProduct ?? application.product).version;
                          const result = scenario.data
                            ? await applicationsApi.confirm(application.id, true, productVersion, scenario.data.revision)
                            : await applicationsApi.confirm(application.id, true, productVersion);
                          apply(result);
                        } catch (nextError) {
                          if (!mounted.current) return;
                          setError(nextError instanceof Error ? nextError.message : 'Не удалось подтвердить заявку');
                          if (nextError instanceof ApiError && nextError.status === 409) {
                            setConfirmConsent(false);
                            void scenario.reload();
                          }
                        } finally {
                          if (mounted.current) setConfirmBusy(false);
                        }
                      }}
                    >
                      {confirmBusy ? 'Отправляем…' : 'Подтвердить и отправить в банк'}
                    </Button>
                  )}
                  {application.allowedActions.includes('RETURN_TO_AGENT') && (
                    <Button
                      variant="outlined"
                      color="warning"
                      size="large"
                      disabled={confirmBusy}
                      onClick={() => setReturning(true)}
                    >
                      Вернуть агенту
                    </Button>
                  )}
                </Stack>
              </Paper>
            )}
            {application.status === 'INFO_REQUESTED' && application.process.infoRequest && (
              <InfoRequestBlock application={application} onChanged={load} onSent={apply} />
            )}
            {application.process.offer && ['OFFER_ISSUED', 'SIGNING', 'DISBURSING'].includes(application.status) && (
              <OfferBlock application={application} onChanged={apply} />
            )}
            {application.status === 'SIGNING' && <SigningBlock application={application} onChanged={apply} />}
            {application.status === 'DISBURSED' && application.process.loan && <LoanBlock application={application} />}
            {application.status === 'DISBURSED' && <LoanServicingBlock applicationId={application.id} />}
            {application.status === 'DISBURSED' && application.process.loan && <GeneratedFilesBlock applicationId={application.id} final />}
            {application.status === 'DISBURSED' && application.process.offer && <Box component="details"><Typography component="summary" sx={{ cursor: 'pointer', py: 1 }}>Согласованные условия</Typography><OfferBlock historical application={application} onChanged={apply} /></Box>}
            {outcome && (
              <Paper variant="outlined" sx={{ p: { xs: 2, md: 3 }, borderColor: 'error.main' }}>
                <Typography variant="h6" component="h2">{outcome.reasonTitle ?? 'Банк не может выдать кредит'}</Typography>
                <Stack component="ul" spacing={0.5} sx={{ pl: 2.5, mb: 0 }}>
                  {outcome.explanation.flatMap((text) => text.split('\n')).map((line) => <Typography component="li" key={line}>{line}</Typography>)}
                </Stack>
                <Button component={RouterLink} to="/client/new" variant="outlined" sx={{ mt: 2 }}>Подобрать другой вариант</Button>
              </Paper>
            )}

            {application.process.offer && ['OFFER_ISSUED', 'SIGNING', 'DISBURSING'].includes(application.status) && <GeneratedFilesBlock key={`${application.id}-${!!application.process.loan}`} applicationId={application.id} final={!!application.process.loan} />}
            <DocumentsBlock application={application} onChanged={load} />
            <MessagesBlock key={application.id} applicationId={application.id} status={application.status} />
            <HistoryBlock applicationId={application.id} version={`${application.status}-${application.documents.length}`} />
          </Stack>
        </Grid>

        <Grid item xs={12} md={4}>
          <Paper variant="outlined" component="aside" aria-label="Параметры заявки" sx={{ p: { xs: 2, md: 3 }, position: { md: 'sticky' }, top: { md: 96 } }}>
            <Typography variant="body2" color="text.secondary">{application.product.name}</Typography>
            <Typography variant="h5" component="p" sx={{ mt: 0.5 }}>{rub(application.amount)}</Typography>
            <Typography color="text.secondary">на {months(application.termMonths)}</Typography>
            {application.estimate && application.status !== 'DRAFT' && !application.process.offer && (
              <Box sx={{ mt: 2, p: 2, borderRadius: '16px', bgcolor: 'background.default' }}>
                <Typography variant="body2" color="text.secondary">Платёж по базовой ставке {percent(application.estimate.rate)}</Typography>
                <Typography variant="subtitle1" component="p" fontWeight={700}>{rubExact(application.estimate.monthlyPayment)} в месяц</Typography>
              </Box>
            )}
            <Divider sx={{ my: 2 }} />
            <Stack spacing={1.5}>
              <Fact label="Цель" value={application.purposeTitle} />
              <Fact label="Компания" value={application.company.name ?? `ИНН ${application.company.inn}`} />
              <Fact label={application.submittedAt ? 'Отправлена' : 'Создана'} value={formatDateTime(application.submittedAt ?? application.createdAt)} />
            </Stack>
            {canWithdraw && (
              <Button color="error" onClick={() => setWithdrawing(true)} sx={{ mt: 2, ml: -1 }}>Отозвать заявку</Button>
            )}
          </Paper>
        </Grid>
      </Grid>

      <Dialog open={returning} onClose={() => setReturning(false)} fullWidth maxWidth="sm">
        <DialogTitle>Вернуть заявку агенту на доработку?</DialogTitle>
        <DialogContent>
          <Typography gutterBottom>Заявка вернётся в статус «Черновик», и агент сможет скорректировать параметры или документы.</Typography>
          <TextField
            label="Комментарий для агента"
            fullWidth
            multiline
            minRows={2}
            value={returnComment}
            onChange={(event) => setReturnComment(event.target.value)}
            inputProps={{ maxLength: 2000 }}
          />
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setReturning(false)}>Отмена</Button>
          <Button
            color="warning"
            variant="contained"
            disabled={confirmBusy}
            onClick={async () => {
              setConfirmBusy(true);
              try {
                apply(await applicationsApi.returnToAgent(application.id, returnComment.trim() || undefined));
                if (!mounted.current) return;
                setReturning(false);
                setReturnComment('');
                setConfirmConsent(false);
              } catch (nextError) {
                if (!mounted.current) return;
                setError(nextError instanceof Error ? nextError.message : 'Не удалось вернуть заявку агенту');
                setReturning(false);
              } finally {
                if (mounted.current) setConfirmBusy(false);
              }
            }}
          >
            Отправить на доработку
          </Button>
        </DialogActions>
      </Dialog>

      <Dialog open={withdrawing} onClose={() => setWithdrawing(false)} fullWidth maxWidth="sm">
        <DialogTitle>Отозвать заявку?</DialogTitle>
        <DialogContent>
          <Typography gutterBottom>Банк прекратит рассмотрение. Позже можно подать новую заявку.</Typography>
          <TextField label="Причина (необязательно)" fullWidth multiline minRows={2} value={withdrawComment} onChange={(event) => setWithdrawComment(event.target.value)} inputProps={{ maxLength: 2000 }} />
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setWithdrawing(false)}>Не отзывать</Button>
          <Button
            color="error"
            onClick={async () => {
              try {
                apply(await applicationsApi.withdraw(application.id, withdrawComment.trim() || undefined));
                if (!mounted.current) return;
                setWithdrawing(false);
              } catch (nextError) {
                if (!mounted.current) return;
                setError(nextError instanceof Error ? nextError.message : 'Не удалось отозвать заявку');
                setWithdrawing(false);
              }
            }}
          >
            Отозвать
          </Button>
        </DialogActions>
      </Dialog>
    </>
  );
}
