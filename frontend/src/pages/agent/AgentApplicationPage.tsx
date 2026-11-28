import {
  Alert,
  Box,
  Button,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Divider,
  Grid,
  IconButton,
  List,
  ListItem,
  ListItemText,
  Paper,
  Stack,
  TextField,
  Typography
} from '@mui/material';
import ArrowBackRoundedIcon from '@mui/icons-material/ArrowBackRounded';
import CheckCircleRoundedIcon from '@mui/icons-material/CheckCircleRounded';
import DeleteOutlineRoundedIcon from '@mui/icons-material/DeleteOutlineRounded';
import DownloadOutlinedIcon from '@mui/icons-material/DownloadOutlined';
import RadioButtonUncheckedRoundedIcon from '@mui/icons-material/RadioButtonUncheckedRounded';
import { BusinessUploadButton } from '../../components/BusinessUploadButton';
import { businessUploadScope } from '../../shared/businessUpload';
import { useCallback, useEffect, useRef, useState } from 'react';
import { Link as RouterLink, useLocation, useParams } from 'react-router-dom';
import { agentApi, ApiError, productsApi } from '../../api/client';
import { DraftConflictAlert } from '../../components/DraftConflictAlert';
import { ErrorState } from '../../components/ErrorState';
import { LoadingBlock } from '../../components/LoadingBlock';
import { LoanServicingBlock } from '../../components/LoanServicingBlock';
import { MessagesBlock } from '../../components/MessagesBlock';
import { MoneyField } from '../../components/MoneyField';
import { PageHeader } from '../../components/PageHeader';
import { SecondaryActionButton } from '../../components/SecondaryActionButton';
import { SensitiveDataNotice } from '../../components/SensitiveDataNotice';
import { useApi } from '../../hooks/useApi';
import { useVisibleRefresh } from '../../hooks/useVisibleRefresh';
import { agentNextAction, SYSTEM_STATUSES, statusTitle, statusTone } from '../../shared/applicationText';
import { fileSize, formatDateTime, months, percent, rub, rubExact } from '../../shared/format';
import { commissionStatusLabels } from '../../shared/labels';
import { transparentOutlineButtonSx } from '../../theme/theme';
import { Application, ApplicationInput, DocumentType } from '../../types';
import { HistoryBlock } from '../client/application/HistoryBlock';
import { StageStepper } from '../client/application/StageStepper';
import { FinancingScenarioEditor } from './FinancingScenarioEditor';

const FAST_REFRESH_MS = 2000;
const SLOW_REFRESH_MS = 15000;

function AgentUploadButton({
  applicationId,
  documentType,
  onUploaded,
  onError,
  label = 'Загрузить',
  variant = 'outlined'
}: {
  applicationId: string;
  documentType: DocumentType;
  onUploaded: () => void;
  onError: (message: string) => void;
  label?: string;
  variant?: 'outlined' | 'text';
}) {
  return <BusinessUploadButton
    scope={() => businessUploadScope('agent', applicationId, documentType)}
    upload={(file, key) => agentApi.uploadDocument(applicationId, documentType, file, key)}
    onUploaded={onUploaded} onError={onError} variant={variant}>{label}</BusinessUploadButton>;
}

function Fact({ label, value }: { label: string; value: string }) {
  return (
    <Box>
      <Typography variant="body2" color="text.secondary">{label}</Typography>
      <Typography fontWeight={500}>{value}</Typography>
    </Box>
  );
}

const clientWaitingMessage = (status: Application['status']): string | null => {
  switch (status) {
    case 'PENDING_CLIENT_CONFIRMATION':
      return 'Заявка отправлена клиенту на подтверждение. Только представитель компании может дать согласие на запрос в БКИ и отправить заявку в банк.';
    case 'OFFER_ISSUED':
      return 'Банк сформировал предложение по заявке. Только клиент может принять или отклонить условия — агент не может принимать предложение за клиента.';
    case 'SIGNING':
      return 'Договор ожидает подписания. Подписать договор одноразовым кодом может только представитель компании с правом подписи.';
    default:
      return null;
  }
};

export function AgentApplicationPage() {
  const { id = '' } = useParams();
  return <AgentApplicationCard key={id} id={id} />;
}

function AgentApplicationCard({ id }: { id: string }) {
  const { hash } = useLocation();
  const products = useApi(productsApi.list);
  const [application, setApplication] = useState<Application | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [docError, setDocError] = useState<string | null>(null);
  const [busyAction, setBusyAction] = useState<string | null>(null);
  const [cancelDialogOpen, setCancelDialogOpen] = useState(false);
  const [infoAnswer, setInfoAnswer] = useState('');
  const [scenarioDirty, setScenarioDirty] = useState(false);
  const [scenarioRevision, setScenarioRevision] = useState<number | null>(null);
  const [scenarioReload, setScenarioReload] = useState(0);

  // Draft editor local state
  const [amount, setAmount] = useState<number>(0);
  const [termMonths, setTermMonths] = useState<number>(12);
  const [purposeComment, setPurposeComment] = useState<string>('');
  const [declaredRevenue12m, setDeclaredRevenue12m] = useState<number | null>(null);
  const [declaredMonthlyDebtPayments, setDeclaredMonthlyDebtPayments] = useState<number | null>(null);

  const [draftConflict, setDraftConflict] = useState<'save' | 'handoff' | null>(null);
  const [draftDirty, setDraftDirty] = useState(false);
  const draftVersion = useRef<number | null>(null);
  const dirty = useRef(false);
  const handingOff = useRef(false);
  const mounted = useRef(false);
  const inFlight = useRef<number | null>(null);
  const requestSequence = useRef(0);

  const syncDraftFields = useCallback((app: Application) => {
    setAmount(app.amount);
    setTermMonths(app.termMonths);
    setPurposeComment(app.purposeComment ?? '');
    setDeclaredRevenue12m(app.declaredRevenue12m);
    setDeclaredMonthlyDebtPayments(app.declaredMonthlyDebtPayments);
    draftVersion.current = app.version;
    dirty.current = false;
    setDraftDirty(false);
  }, []);

  const editDraft = (update: () => void) => {
    if (busyAction !== null || !canEditDraft) return;
    dirty.current = true;
    setDraftDirty(true);
    update();
  };

  const apply = useCallback((next: Application) => {
    if (!mounted.current || next.id !== id) return;
    requestSequence.current += 1;
    setApplication(next);
    setError(null);
  }, [id]);

  const load = useCallback(async () => {
    if (!mounted.current || inFlight.current !== null) return;
    const sequence = ++requestSequence.current;
    inFlight.current = sequence;
    try {
      const next = await agentApi.getApplication(id);
      if (!mounted.current || sequence !== requestSequence.current || next.id !== id) return;
      setApplication(next);
      setError(null);
      if (!dirty.current && !handingOff.current) syncDraftFields(next);
    } catch (nextError) {
      if (!mounted.current || sequence !== requestSequence.current) return;
      if (!dirty.current) setApplication(null);
      setError(nextError instanceof Error ? nextError.message : 'Не удалось загрузить заявку');
    } finally {
      if (inFlight.current === sequence) inFlight.current = null;
    }
  }, [id, syncDraftFields]);

  useEffect(() => {
    mounted.current = true;
    void load();
    return () => { mounted.current = false; inFlight.current = null; requestSequence.current += 1; };
  }, [load]);

  const status = application?.status;
  useVisibleRefresh(load, status && SYSTEM_STATUSES.includes(status) ? FAST_REFRESH_MS : SLOW_REFRESH_MS);

  useEffect(() => {
    if (status && hash) document.getElementById(hash.slice(1))?.scrollIntoView?.({ block: 'start' });
  }, [hash, status]);

  if (error && !application) return <ErrorState message={error} onRetry={load} />;
  if (!application) return <LoadingBlock />;

  const tone = statusTone(application.status);
  const nextStep = agentNextAction(application.status);
  const actionLabel = application.actionLabel ?? nextStep.label;
  const actionRequiresAgent = application.actionOwner === undefined ? nextStep.requiresAgent : application.actionOwner === 'AGENT';
  const waitingBanner = clientWaitingMessage(application.status);
  const product = application.currentProduct ?? products.data?.find((item) => item.code === application.product.code);
  const canRequestConfirmation = application.allowedActions.includes('REQUEST_CLIENT_CONFIRMATION');
  const canCancel = application.allowedActions.includes('AGENT_CANCEL');
  const canProvideInfo = application.status === 'INFO_REQUESTED' && application.allowedActions.includes('INFO_PROVIDED') &&
    application.actionOwner !== 'CLIENT' && !!application.process.infoRequest;
  const isDraft = application.status === 'DRAFT';
  const canEditDraft = isDraft && application.editable;
  const needsResolution = draftConflict !== null || (draftDirty && !canEditDraft);
  const expectedScenarioRevision = application.product.code === 'WORKING_CAPITAL' ? scenarioRevision : 0;

  const persistDraft = async (expectedVersion = draftVersion.current): Promise<Application> => {
    if (expectedVersion == null) throw new Error('Обновите заявку, чтобы получить версию черновика.');
    const input: ApplicationInput = {
      productCode: application.product.code,
      purpose: application.purpose,
      purposeComment: purposeComment.trim() || null,
      amount,
      termMonths,
      declaredRevenue12m,
      declaredMonthlyDebtPayments,
      bureauConsent: false
    };
    const saved = await agentApi.updateApplication(application.id, input, expectedVersion);
    if (mounted.current) {
      apply(saved);
      syncDraftFields(saved);
      setDraftConflict(null);
    }
    return saved;
  };

  const noteDraftConflict = (kind: 'save' | 'handoff') => {
    dirty.current = true;
    setDraftDirty(true);
    setDraftConflict(kind);
  };

  const showDraftError = (nextError: unknown, fallback: string) => {
    if (!mounted.current) return;
    if (nextError instanceof ApiError && nextError.status === 409) {
      noteDraftConflict('save');
    } else {
      setError(nextError instanceof Error ? nextError.message : fallback);
    }
  };

  const saveDraft = async (): Promise<Application | null> => {
    if (needsResolution || !canEditDraft) return null;
    setBusyAction('save');
    setError(null);
    try {
      return await persistDraft();
    } catch (nextError) {
      showDraftError(nextError, 'Не удалось сохранить черновик');
      return null;
    } finally {
      setBusyAction(null);
    }
  };

  const resolveConflict = async (overwrite: boolean) => {
    setBusyAction('resolve');
    setError(null);
    try {
      const current = await agentApi.getApplication(id);
      if (!mounted.current) return;
      apply(current);
      if (overwrite) {
        if (current.status !== 'DRAFT' || !current.editable || current.version == null) {
          throw new Error('Редактирование заявки больше недоступно.');
        }
        await persistDraft(current.version);
      } else {
        syncDraftFields(current);
        if (draftConflict === 'handoff') {
          setScenarioRevision(null);
          setScenarioReload((revision) => revision + 1);
        }
        setDraftConflict(null);
      }
    } catch (nextError) {
      showDraftError(nextError, 'Не удалось обновить заявку');
    } finally {
      if (mounted.current) setBusyAction(null);
    }
  };

  const handleRequestConfirmation = async () => {
    if (scenarioDirty || expectedScenarioRevision === null || needsResolution || !canEditDraft) return;
    handingOff.current = true;
    setBusyAction('confirm-request');
    setError(null);
    try {
      const saved = await persistDraft().catch((nextError) => {
        showDraftError(nextError, 'Не удалось сохранить черновик');
        return null;
      });
      if (!mounted.current || !saved || saved.submissionProblems.length > 0) return;
      if (saved.version == null) throw new Error('Обновите заявку, чтобы получить версию черновика.');
      const next = await agentApi.requestConfirmation(application.id, saved.version, expectedScenarioRevision);
      apply(next);
    } catch (nextError) {
      if (!mounted.current) return;
      if (nextError instanceof ApiError && nextError.conflictCode === 'draft-version') {
        noteDraftConflict('handoff');
      } else {
        setError(nextError instanceof Error ? nextError.message : 'Не удалось отправить заявку клиенту');
      }
    } finally {
      handingOff.current = false;
      if (mounted.current) setBusyAction(null);
    }
  };

  const handleCancel = async () => {
    setBusyAction('cancel');
    setError(null);
    try {
      const next = await agentApi.cancelApplication(application.id);
      apply(next);
      setCancelDialogOpen(false);
    } catch (nextError) {
      setError(nextError instanceof Error ? nextError.message : 'Не удалось отменить черновик');
      setCancelDialogOpen(false);
    } finally {
      setBusyAction(null);
    }
  };

  const handleProvideInfo = async () => {
    setBusyAction('info');
    setError(null);
    try {
      const next = await agentApi.provideInfo(application.id, infoAnswer.trim() || undefined);
      apply(next);
      setInfoAnswer('');
    } catch (nextError) {
      setError(nextError instanceof Error ? nextError.message : 'Не удалось отправить документы в банк');
    } finally {
      setBusyAction(null);
    }
  };

  const infoRequest = application.process.infoRequest;
  const infoRequestedAt = infoRequest ? new Date(infoRequest.requestedAt).getTime() : 0;
  const uploadedSinceRequest = (type: string) =>
    application.documents.some((doc) => doc.type === type && new Date(doc.uploadedAt).getTime() >= infoRequestedAt);
  const infoHasQuestions = infoRequest ? infoRequest.items.some((item) => item.question) : false;
  const infoReady = infoRequest
    ? infoRequest.items.every((item) => !item.documentType || (item.satisfied ?? uploadedSinceRequest(item.documentType))) &&
      (!infoHasQuestions || infoAnswer.trim().length > 0)
    : false;

  return (
    <>
      <Button component={RouterLink} to="/agent" startIcon={<ArrowBackRoundedIcon />} sx={{ mb: 1 }}>
        Портфель агента
      </Button>

      <PageHeader
        title={`Заявка${application.number ? ` № ${application.number}` : ''}`}
        subtitle={`${application.company.name ?? `ИНН ${application.company.inn}`} · ${application.product.name} · ${rub(application.amount)}`}
        actions={<Chip label={statusTitle(application.status)} color={tone === 'default' ? 'default' : tone} sx={{ alignSelf: { xs: 'flex-start', md: 'center' } }} />}
      />

      <Grid container spacing={3} sx={{ pb: 6 }}>
        <Grid item xs={12} md={8}>
          <Stack spacing={3}>
            <Paper data-tour="agent-app-status" variant="outlined" sx={{ p: { xs: 2, md: 3 } }}>
              <StageStepper status={application.status} />
              <Box sx={{ mt: 2 }}>
                <Typography fontWeight={600}>Следующий шаг</Typography>
                <Typography color={actionRequiresAgent ? 'warning.dark' : 'text.secondary'}>{actionLabel}</Typography>
                {application.actionDeadline && <Typography variant="caption" color="text.secondary">До {formatDateTime(application.actionDeadline)}</Typography>}
              </Box>
            </Paper>

            {waitingBanner && (
              <Alert severity="info">
                {waitingBanner}
              </Alert>
            )}

            {error && <Alert severity="error" onClose={() => setError(null)}>{error}</Alert>}

            {(canEditDraft || draftDirty) && (
              <Paper data-tour="agent-app-draft" id="draft-financing" variant="outlined" sx={{ p: { xs: 2, md: 3 }, scrollMarginTop: 110 }}>
                <Typography variant="h6" component="h2" gutterBottom>Параметры черновика</Typography>
                <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
                  Заполните параметры заявки и приложите обязательные документы. Согласие на запрос в БКИ даст сам клиент на этапе подтверждения.
                </Typography>

                <Grid container spacing={2} component="fieldset" disabled={busyAction !== null || !canEditDraft}
                  sx={{ border: 0, p: 0, minWidth: 0 }}>
                  <Grid item xs={12} sm={6}>
                    <MoneyField
                      label="Сумма"
                      fullWidth
                      value={amount}
                      onChange={(value) => editDraft(() => setAmount(value ?? 0))}
                      helperText={product ? `от ${rub(product.minAmount)} до ${rub(product.maxAmount)}` : ' '}
                    />
                  </Grid>
                  <Grid item xs={12} sm={6}>
                    <TextField
                      label="Срок, месяцев"
                      fullWidth
                      inputProps={{ inputMode: 'numeric' }}
                      value={termMonths || ''}
                      onChange={(event) => editDraft(() => setTermMonths(Number(event.target.value.replace(/\D/g, '').slice(0, 3)) || 0))}
                      helperText={product ? `от ${product.minTermMonths} до ${product.maxTermMonths}` : ' '}
                    />
                  </Grid>
                  <Grid item xs={12}>
                    <TextField
                      label="Комментарий к цели"
                      fullWidth
                      multiline
                      minRows={2}
                      inputProps={{ maxLength: 1000 }}
                      value={purposeComment}
                      onChange={(event) => editDraft(() => setPurposeComment(event.target.value))}
                    />
                  </Grid>
                  <Grid item xs={12} sm={6}>
                    <MoneyField
                      label="Выручка за последние 12 месяцев"
                      fullWidth
                      value={declaredRevenue12m}
                      onChange={(value) => editDraft(() => setDeclaredRevenue12m(value))}
                    />
                  </Grid>
                  <Grid item xs={12} sm={6}>
                    <MoneyField
                      label="Платежи по кредитам, в месяц"
                      fullWidth
                      value={declaredMonthlyDebtPayments}
                      onChange={(value) => editDraft(() => setDeclaredMonthlyDebtPayments(value))}
                    />
                  </Grid>
                </Grid>

                {application.submissionProblems.length > 0 && (
                  <Alert severity="info" sx={{ mt: 2 }}>
                    Перед отправкой клиенту проверьте:
                    <ul style={{ margin: '4px 0 0', paddingLeft: 20 }}>
                      {application.submissionProblems.map((problem) => <li key={problem}>{problem}</li>)}
                    </ul>
                  </Alert>
                )}

                {needsResolution && (
                  <DraftConflictAlert busy={busyAction !== null} canOverwrite={canEditDraft} reviewRequired={draftConflict === 'handoff'}
                    onResolve={(overwrite) => void resolveConflict(overwrite)} />
                )}
                <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5} sx={{ mt: 2.5 }}>
                  <Button variant="outlined" disabled={busyAction !== null || needsResolution} onClick={() => void saveDraft()}>
                    {busyAction === 'save' ? 'Сохраняем…' : 'Сохранить черновик'}
                  </Button>
                  {canRequestConfirmation && (
                    <Button
                      variant="contained"
                      disabled={busyAction !== null || scenarioDirty || expectedScenarioRevision === null || needsResolution}
                      onClick={() => void handleRequestConfirmation()}
                    >
                      {busyAction === 'confirm-request' ? 'Отправляем…' : 'Отправить клиенту на подтверждение'}
                    </Button>
                  )}
                </Stack>
              </Paper>
            )}

            {isDraft && application.editable && application.product.code === 'WORKING_CAPITAL' && (
              <FinancingScenarioEditor key={scenarioReload} application={application} amount={amount} baseTerm={termMonths}
                savingDraft={busyAction !== null} saveDraft={saveDraft} onUncommittedChange={setScenarioDirty}
                onRevisionChange={setScenarioRevision} />
            )}

            {infoRequest && application.status === 'INFO_REQUESTED' && (
              <Paper data-tour="agent-app-info" id="application-info-request" variant="outlined" sx={{ p: { xs: 2, md: 3 }, borderColor: 'warning.main', scrollMarginTop: 110 }}>
                <Typography variant="h6" component="h2">Банк запросил дополнительные документы</Typography>
                <Typography color="text.secondary" sx={{ mb: 2 }}>
                  Ответить нужно до {formatDateTime(infoRequest.deadline)}.{infoRequest.comment ? ` ${infoRequest.comment}` : ''}
                </Typography>
                <SensitiveDataNotice />
                <Stack spacing={1.5}>
                  {infoRequest.items.map((item) => {
                    const done = item.satisfied ?? (item.documentType ? uploadedSinceRequest(item.documentType) : false);
                    return (
                      <Stack key={item.title} direction={{ xs: 'column', sm: 'row' }} spacing={1.5} justifyContent="space-between" alignItems={{ sm: 'center' }}>
                        <Stack direction="row" spacing={1.5} alignItems="flex-start">
                          {done ? <CheckCircleRoundedIcon color="success" /> : <RadioButtonUncheckedRoundedIcon color="disabled" />}
                          <Box>
                            <Typography fontWeight={600}>{item.title}</Typography>
                            {item.reason && <Typography variant="body2" color="text.secondary">{item.reason}</Typography>}
                            {done && <Typography variant="body2" color="success.main">Документ передан</Typography>}
                          </Box>
                        </Stack>
                        {canProvideInfo && item.documentType && !done && (
                          <AgentUploadButton
                            applicationId={application.id}
                            documentType={item.documentType}
                            onUploaded={() => void load()}
                            onError={setDocError}
                          />
                        )}
                      </Stack>
                    );
                  })}
                </Stack>
                {canProvideInfo && infoHasQuestions && (
                  <TextField
                    label="Ответ на вопросы банка"
                    fullWidth
                    multiline
                    minRows={3}
                    sx={{ mt: 2 }}
                    value={infoAnswer}
                    onChange={(event) => setInfoAnswer(event.target.value)}
                    inputProps={{ maxLength: 2000 }}
                  />
                )}
                {canProvideInfo ? (
                  <Button
                    variant="contained"
                    size="large"
                    sx={{ mt: 2 }}
                    disabled={!infoReady || busyAction !== null}
                    onClick={() => void handleProvideInfo()}
                  >
                    {busyAction === 'info' ? 'Отправляем…' : 'Отправить догруженные документы'}
                  </Button>
                ) : <Alert severity="info" sx={{ mt: 2 }}>На запрос ответит представитель компании.</Alert>}
              </Paper>
            )}

            {application.process.offer && (
              <Paper data-tour="agent-app-offer" variant="outlined" sx={{ p: { xs: 2, md: 3 } }}>
                <Typography variant="h6" component="h2" gutterBottom>
                  {application.process.offer.kind === 'COUNTER' ? 'Встречное предложение банка' : 'Одобренное предложение банка'}
                </Typography>
                <Grid container spacing={2}>
                  <Grid item xs={6} sm={3}><Fact label="Сумма" value={rub(application.process.offer.amount)} /></Grid>
                  <Grid item xs={6} sm={3}><Fact label="Срок" value={months(application.process.offer.termMonths)} /></Grid>
                  <Grid item xs={6} sm={3}><Fact label="Ставка" value={percent(application.process.offer.rate)} /></Grid>
                  <Grid item xs={6} sm={3}><Fact label="Платёж" value={rubExact(application.process.offer.monthlyPayment)} /></Grid>
                </Grid>
                <Typography variant="body2" color="text.secondary" sx={{ mt: 1.5 }}>
                  Действительно до {formatDateTime(application.process.offer.validUntil)}. Решение о принятии и подписании принимает клиент.
                </Typography>
              </Paper>
            )}

            {application.status === 'DISBURSED' && (
              <Box data-tour="agent-app-loan"><LoanServicingBlock applicationId={application.id} readOnly agentScope /></Box>
            )}

            {/* Documents block via agent API */}
            {(isDraft || application.documents.length > 0) && (
              <Paper data-tour="agent-app-documents" variant="outlined" sx={{ p: { xs: 2, md: 3 } }}>
                <Typography variant="h6" component="h2" gutterBottom>Документы</Typography>
                {docError && <Alert severity="error" sx={{ mb: 2 }} onClose={() => setDocError(null)}>{docError}</Alert>}
                {isDraft && application.canUploadDocuments && <SensitiveDataNotice />}
                {isDraft && application.requiredDocuments.length > 0 && (
                  <Stack spacing={1.5} sx={{ mb: 2 }}>
                    {application.requiredDocuments.map((req) => (
                      <Stack key={req.type} direction={{ xs: 'column', sm: 'row' }} spacing={1.5} alignItems={{ sm: 'center' }} justifyContent="space-between">
                        <Stack direction="row" spacing={1.5} alignItems="flex-start">
                          {req.uploaded ? <CheckCircleRoundedIcon color="success" /> : <RadioButtonUncheckedRoundedIcon color="disabled" />}
                          <Box>
                            <Typography fontWeight={600}>{req.title}</Typography>
                            <Typography variant="body2" color="text.secondary">{req.reason}</Typography>
                          </Box>
                        </Stack>
                        {application.canUploadDocuments && !req.uploaded && (
                          <AgentUploadButton
                            applicationId={application.id}
                            documentType={req.type}
                            onUploaded={() => void load()}
                            onError={setDocError}
                          />
                        )}
                      </Stack>
                    ))}
                  </Stack>
                )}

                {application.documents.length > 0 && (
                  <>
                    <Typography variant="subtitle2" color="text.secondary">Загружено</Typography>
                    <List dense disablePadding>
                      {application.documents.map((document) => (
                        <ListItem
                          key={document.id}
                          disableGutters
                          secondaryAction={isDraft && application.editable && (
                            <IconButton
                              edge="end"
                              aria-label={`Удалить ${document.fileName}`}
                              onClick={() => agentApi.deleteDocument(application.id, document.id).then(() => void load()).catch((e: Error) => setDocError(e.message))}
                            >
                              <DeleteOutlineRoundedIcon />
                            </IconButton>
                          )}
                        >
                          <ListItemText
                            sx={{ minWidth: 0, overflowWrap: 'anywhere' }}
                            primary={
                              <SecondaryActionButton
                                size="small"
                                startIcon={<DownloadOutlinedIcon />}
                                sx={{ minHeight: 44, textAlign: 'left', overflowWrap: 'anywhere' }}
                                onClick={() => agentApi.downloadDocument(application.id, document.id, document.fileName).catch((e: Error) => setDocError(e.message))}
                              >
                                {document.fileName}
                              </SecondaryActionButton>
                            }
                            secondary={`${document.title} · ${fileSize(document.sizeBytes)} · ${formatDateTime(document.uploadedAt)}`}
                          />
                        </ListItem>
                      ))}
                    </List>
                  </>
                )}

                {isDraft && application.canUploadDocuments && (
                  <Box sx={{ mt: 1 }}>
                    <AgentUploadButton
                      applicationId={application.id}
                      documentType="OTHER"
                      label="Добавить другой документ"
                      variant="text"
                      onUploaded={() => void load()}
                      onError={setDocError}
                    />
                  </Box>
                )}
              </Paper>
            )}

            <Box data-tour="agent-app-messages"><MessagesBlock key={application.id} applicationId={application.id} status={application.status} /></Box>
            <HistoryBlock agentScope applicationId={application.id} version={`${application.status}-${application.documents.length}`} />
          </Stack>
        </Grid>

        <Grid item xs={12} md={4}>
          <Paper data-tour="agent-app-summary" variant="outlined" component="aside" aria-label="Сводка заявки" sx={{ p: { xs: 2, md: 3 }, position: { md: 'sticky' }, top: { md: 96 } }}>
            <Typography variant="body2" color="text.secondary">{application.product.name}</Typography>
            <Typography variant="h5" component="p" sx={{ mt: 0.5 }}>{rub(application.amount)}</Typography>
            <Typography color="text.secondary">на {months(application.termMonths)}</Typography>

            {application.estimate && !application.process.offer && (
              <Box sx={{ mt: 2, p: 2, borderRadius: '16px', bgcolor: 'background.default' }}>
                <Typography variant="body2" color="text.secondary">Платёж по базовой ставке {percent(application.estimate.rate)}</Typography>
                <Typography variant="subtitle1" component="p" fontWeight={700}>{rubExact(application.estimate.monthlyPayment)} в месяц</Typography>
              </Box>
            )}

            <Divider sx={{ my: 2 }} />
            <Stack spacing={1.5}>
              <Fact label="Компания" value={application.company.name ?? `ИНН ${application.company.inn}`} />
              <Fact label="ИНН" value={application.company.inn} />
              <Fact label="Цель" value={application.purposeTitle} />
              <Fact label={application.submittedAt ? 'Отправлена в банк' : 'Создана'} value={formatDateTime(application.submittedAt ?? application.createdAt)} />
              {application.agentCommissionAmount != null && application.agentCommissionStatus && application.agentCommissionStatus !== 'NONE' && (
                <Fact
                  label="Комиссия агента"
                  value={`${rub(application.agentCommissionAmount)} (${commissionStatusLabels[application.agentCommissionStatus]})`}
                />
              )}
            </Stack>

            {canCancel && (
              <Button variant="outlined" size="small" onClick={() => setCancelDialogOpen(true)} sx={{ ...transparentOutlineButtonSx, mt: 2 }}>
                Отменить черновик
              </Button>
            )}
          </Paper>
        </Grid>
      </Grid>

      <Dialog open={cancelDialogOpen} onClose={() => setCancelDialogOpen(false)} fullWidth maxWidth="sm">
        <DialogTitle>Отменить черновик заявки?</DialogTitle>
        <DialogContent>
          <Typography>Заявка перейдёт в статус «Отозвана». При необходимости вы сможете создать новую заявку для этого клиента.</Typography>
        </DialogContent>
        <DialogActions disableSpacing sx={(theme) => ({
          flexWrap: 'wrap',
          gap: 1,
          [theme.breakpoints.down('sm')]: {
            '& .MuiButton-root': { px: 1.5, fontSize: '0.875rem' },
          },
        })}>
          <Button variant="outlined" onClick={() => setCancelDialogOpen(false)} sx={transparentOutlineButtonSx}>Не отменять</Button>
          <Button variant="contained" color="error" disabled={busyAction !== null} onClick={() => void handleCancel()}>
            {busyAction === 'cancel' ? 'Отменяем…' : 'Отменить черновик'}
          </Button>
        </DialogActions>
      </Dialog>
    </>
  );
}
