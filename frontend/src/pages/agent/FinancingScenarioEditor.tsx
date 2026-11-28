import { Alert, Box, Button, FormControlLabel, Grid, Paper, Radio, RadioGroup, Stack, TextField, Typography } from '@mui/material';
import { ChangeEvent, useEffect, useRef, useState } from 'react';
import { agentApi } from '../../api/client';
import { extractInvoice, financingApi, FinancingInputs, FinancingScenario, InvoiceExtraction } from '../../api/financing';
import { FinancingComparison } from '../../components/FinancingScenarioView';
import { MoneyField } from '../../components/MoneyField';
import { KeshaDelayHint } from '../../components/kesha/KeshaDelayHint';
import { formatDate, months, rub } from '../../shared/format';
import { Application } from '../../types';
import { BusinessUploadOperation, businessUploadScope, createBusinessUpload } from '../../shared/businessUpload';

interface Props {
  application: Application;
  amount: number;
  baseTerm: number;
  savingDraft: boolean;
  saveDraft: () => Promise<Application | null>;
  onUncommittedChange: (dirty: boolean) => void;
}

type ScenarioForm = Omit<FinancingInputs, 'openingBalance' | 'expectedReceiptAmount' | 'source'> & {
  openingBalance: number | null;
  expectedReceiptAmount: number | null;
};

function defaultInputs(baseTerm: number): ScenarioForm {
  return {
    openingBalance: null,
    expectedReceiptAmount: null,
    expectedReceiptDate: '',
    paymentDate: '',
    delayDays: 0,
    alternativeTermMonths: baseTerm === 24 ? 12 : 24,
    selectedTermMonths: baseTerm
  };
}

const invoiceError = 'Впишите сумму и дату вручную. Черновик заявки сохранится.';

export function FinancingScenarioEditor({ application, amount, baseTerm, savingDraft, saveDraft, onUncommittedChange }: Props) {
  const id = application.id;
  const latestId = useRef(id);
  latestId.current = id;
  const fileInput = useRef<HTMLInputElement>(null);
  const [scenario, setScenario] = useState<FinancingScenario | null>(null);
  const [form, setForm] = useState<ScenarioForm>(() => defaultInputs(baseTerm));
  const invoiceUpload = useRef<BusinessUploadOperation | null>(null);
  const [invoiceDocumentId, setInvoiceDocumentId] = useState<string | null>(null);
  const [extraction, setExtraction] = useState<InvoiceExtraction | null>(null);
  const [selectedSourceDate, setSelectedSourceDate] = useState('');
  const [useSourceAmount, setUseSourceAmount] = useState(false);
  const [invoiceNotice, setInvoiceNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<'load' | 'calculate' | 'extract' | null>('load');
  const [dirty, setDirty] = useState(false);
  const delayTarget = useRef<HTMLDivElement>(null);
  const delayInput = useRef<HTMLInputElement>(null);
  const delayRevision = useRef(0);
  const undoKesha = useRef<{ previousDays: number; revision: number } | null>(null);
  const [scenarioLoadedFor, setScenarioLoadedFor] = useState<string | null>(null);
  const [keshaPatched, setKeshaPatched] = useState(false);
  const modified = dirty || keshaPatched;
  const [sourceManuallyEdited, setSourceManuallyEdited] = useState(false);
  const savedTerms = useRef({ amount, baseTerm });
  const onUncommittedChangeRef = useRef(onUncommittedChange);
  onUncommittedChangeRef.current = onUncommittedChange;
  const conflictingTerms = !!scenario && (savedTerms.current.amount !== amount || savedTerms.current.baseTerm !== baseTerm);

  useEffect(() => {
    let active = true;
    const scope = businessUploadScope('invoice', id);
    invoiceUpload.current = null;
    setInvoiceDocumentId(null);
    void financingApi.agent(id).then((previous) => {
      if (!active || businessUploadScope('invoice', id) !== scope || previous?.status === 'CONFIRMED') return;
      setScenarioLoadedFor(id);
      if (!previous) return;
      setScenario(previous);
      setForm(previous.inputs);
    }).catch((next) => {
      if (active && businessUploadScope('invoice', id) === scope) setError(next instanceof Error ? next.message : 'Не удалось прочитать примерку');
    }).finally(() => {
      if (active) setBusy(null);
    });
    return () => { active = false; onUncommittedChangeRef.current(false); };
  }, [id]);

  useEffect(() => {
    onUncommittedChangeRef.current(modified || conflictingTerms || busy === 'calculate');
  }, [modified, conflictingTerms, busy]);

  function invalidateDelayOffer() {
    delayRevision.current += 1;
    undoKesha.current = null;
    setKeshaPatched(false);
  }

  function change<K extends keyof ScenarioForm>(name: K, value: ScenarioForm[K]) {
    if (name === 'delayDays') invalidateDelayOffer();
    setForm((previous) => ({ ...previous, [name]: value }));
    setDirty(true);
    if (name === 'expectedReceiptAmount') {
      setUseSourceAmount(false);
      setSourceManuallyEdited(true);
    }
    if (name === 'expectedReceiptDate') {
      setSelectedSourceDate('');
      setSourceManuallyEdited(true);
    }
  }

  function discardInvoiceUpload() {
    invoiceUpload.current = null;
    setInvoiceDocumentId(null);
    setExtraction(null);
    setUseSourceAmount(false);
    setSelectedSourceDate('');
    setSourceManuallyEdited(true);
    setInvoiceNotice(null);
    setError('Выберите файл заново, чтобы продолжить.');
  }

  function isCurrentInvoiceScope(scope: string) {
    if (businessUploadScope('invoice', latestId.current) === scope) return true;
    discardInvoiceUpload();
    return false;
  }

  const readFile = async (event: ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    event.target.value = '';
    if (!file) return;
    setBusy('extract');
    setError(null);
    setExtraction(null);
    const operation = createBusinessUpload(file, businessUploadScope('invoice', id));
    invoiceUpload.current = operation;
    setInvoiceDocumentId(null);
    setSelectedSourceDate('');
    setUseSourceAmount(false);
    setSourceManuallyEdited(false);
    setInvoiceNotice(null);
    try {
      const found = await extractInvoice(id, file);
      if (!isCurrentInvoiceScope(operation.scope) || invoiceUpload.current !== operation) return;
      setExtraction(found);
      setInvoiceNotice(found.amount || found.dates.length
        ? 'Проверьте значения. При сохранении выбранных строк счёт приложится к заявке. Также можно ввести данные вручную.'
        : invoiceError);
    } catch {
      if (!isCurrentInvoiceScope(operation.scope)) return;
      setInvoiceNotice(invoiceError);
    } finally {
      setBusy(null);
    }
  };

  function applyInvoiceDate(date: string) {
    setSelectedSourceDate(date);
    setForm((previous) => ({ ...previous, expectedReceiptDate: date }));
    setDirty(true);
  }

  function verifiedSource() {
    const invoiceAmount = extraction?.amount;
    const invoiceDate = extraction?.dates.find((candidate) => candidate.value === selectedSourceDate);
    if (!useSourceAmount || !invoiceAmount || !invoiceDate || invoiceAmount.page !== invoiceDate.page ||
      invoiceAmount.value !== form.expectedReceiptAmount || invoiceDate.value !== form.expectedReceiptDate) return undefined;
    return { page: invoiceAmount.page, amountQuote: invoiceAmount.quote, dateQuote: invoiceDate.quote };
  }

  const enabled = !!baseTerm && !!form.alternativeTermMonths && form.alternativeTermMonths !== baseTerm &&
    form.openingBalance != null && form.expectedReceiptAmount != null &&
    !!form.expectedReceiptDate && !!form.paymentDate && !busy && !savingDraft;

  async function calculate() {
    if (!enabled || form.openingBalance == null || form.expectedReceiptAmount == null) return;
    const scope = businessUploadScope('invoice', id);
    if (invoiceUpload.current && invoiceUpload.current.scope !== scope) {
      discardInvoiceUpload();
      return;
    }
    setBusy('calculate');
    setError(null);
    try {
      const saved = await saveDraft();
      if (!isCurrentInvoiceScope(scope) || !saved) return;
      const selectedTermMonths = form.selectedTermMonths === form.alternativeTermMonths ? form.selectedTermMonths : saved.termMonths;
      const verified = verifiedSource();
      let source: FinancingInputs['source'] = !extraction && !sourceManuallyEdited ? scenario?.inputs.source : undefined;
      if (verified && invoiceUpload.current) {
        if (invoiceUpload.current.scope !== scope) {
          discardInvoiceUpload();
          return;
        }
        const { file, key } = invoiceUpload.current;
        const documentId = invoiceDocumentId ?? (await agentApi.uploadDocument(id, 'OTHER', file, key)).id;
        if (!isCurrentInvoiceScope(scope)) return;
        setInvoiceDocumentId(documentId);
        source = { ...verified, documentId };
      }
      const next = await financingApi.save(id, {
        ...form,
        openingBalance: form.openingBalance,
        expectedReceiptAmount: form.expectedReceiptAmount,
        selectedTermMonths,
        source
      });
      if (!isCurrentInvoiceScope(scope)) return;
      setScenario(next);
      setForm(next.inputs);
      savedTerms.current = { amount: saved.amount, baseTerm: saved.termMonths };
      invalidateDelayOffer();
      setDirty(false);
    } catch (next) {
      if (!isCurrentInvoiceScope(scope)) return;
      setError(next instanceof Error ? next.message : 'Не удалось рассчитать варианты');
    } finally {
      setBusy(null);
    }
  }

  const fieldDisabled = busy !== null || savingDraft;
  const keshaAvailable = !fieldDisabled && scenarioLoadedFor === id && application.editable && application.status === 'DRAFT' &&
    application.product.code === 'WORKING_CAPITAL';

  function applyKeshaDelay(expectedDays: number, revision: number) {
    if (!keshaAvailable || latestId.current !== id || undoKesha.current || revision !== delayRevision.current ||
      form.delayDays !== expectedDays) return false;
    undoKesha.current = { previousDays: expectedDays, revision };
    setKeshaPatched(true);
    setForm((previous) => ({ ...previous, delayDays: 2 }));
    return true;
  }

  function undoKeshaDelay() {
    const patch = undoKesha.current;
    if (!keshaAvailable || latestId.current !== id || !patch || patch.revision !== delayRevision.current) return false;
    invalidateDelayOffer();
    setForm((previous) => ({ ...previous, delayDays: patch.previousDays }));
    return true;
  }

  return (
    <Paper variant="outlined" sx={{ p: { xs: 2, md: 3 }, scrollMarginTop: 110 }}>
      <Stack spacing={2}>
        <Typography variant="h6" component="h2">Примерка кредита</Typography>
        <Typography variant="body2" color="text.secondary">Сравните, сколько денег останется в дату платежа при задержке оплаты по счёту. Для передачи сохраните выбранный вариант.</Typography>
        {error && <Alert severity="warning">{error}</Alert>}
        {invoiceNotice && <Alert severity="info">{invoiceNotice}</Alert>}
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1}>
          <Button variant="outlined" disabled={fieldDisabled} onClick={() => fileInput.current?.click()}>
            {busy === 'extract' ? 'Читаем счёт…' : 'Прочитать счёт PDF'}
          </Button>
          <input aria-label="Счёт PDF для примерки" ref={fileInput} hidden type="file" accept=".pdf,application/pdf" onChange={(event) => void readFile(event)} />
        </Stack>
        {extraction && (
          <Stack spacing={0.5}>
            {extraction.amount && (
              <Stack spacing={0.5} alignItems="flex-start">
                <Typography variant="body2">{rub(extraction.amount.value)} · стр. {extraction.amount.page} · {extraction.amount.quote}</Typography>
                <Button disabled={fieldDisabled} onClick={() => {
                  const candidate = extraction.amount;
                  if (!candidate) return;
                  change('expectedReceiptAmount', candidate.value);
                  setUseSourceAmount(true);
                }}>Использовать сумму из счёта</Button>
              </Stack>
            )}
            {extraction.dates.length > 0 && (
              <RadioGroup aria-label="Подтвердить дату поступления" value={selectedSourceDate} onChange={(_, value) => applyInvoiceDate(value)}>
                {extraction.dates.map((candidate) => (
                  <FormControlLabel key={candidate.value} value={candidate.value} control={<Radio />} disabled={fieldDisabled}
                    label={`${formatDate(candidate.value)} · стр. ${candidate.page} · ${candidate.quote}`} />
                ))}
              </RadioGroup>
            )}
          </Stack>
        )}
        <Grid container spacing={1.5}>
          <Grid item xs={12} sm={6}><MoneyField fullWidth label="Остаток до поступления" disabled={fieldDisabled} value={form.openingBalance} onChange={(value) => change('openingBalance', value)} /></Grid>
          <Grid item xs={12} sm={6}><MoneyField fullWidth label="Сумма ожидаемого поступления" disabled={fieldDisabled} value={form.expectedReceiptAmount} onChange={(value) => change('expectedReceiptAmount', value)} /></Grid>
          <Grid item xs={12} sm={6}><TextField fullWidth label="Дата ожидаемого поступления" type="date" InputLabelProps={{ shrink: true }} value={form.expectedReceiptDate} disabled={fieldDisabled} onChange={(event) => change('expectedReceiptDate', event.target.value)} /></Grid>
          <Grid item xs={12} sm={6}><TextField fullWidth label="Дата платежа" type="date" InputLabelProps={{ shrink: true }} value={form.paymentDate} disabled={fieldDisabled} onChange={(event) => change('paymentDate', event.target.value)} /></Grid>
          <Grid item xs={12} sm={6}>
            <Box ref={delayTarget} data-testid="kesha-delay-target">
              <TextField fullWidth label="Задержка поступления, дней" type="number" inputRef={delayInput} inputProps={{ min: 0, max: 365 }}
                value={form.delayDays} disabled={fieldDisabled} onChange={(event) => change('delayDays', Number(event.target.value))} />
            </Box>
            <KeshaDelayHint applicationId={id} target={delayTarget} delayInput={delayInput} delayDays={form.delayDays}
              delayRevision={delayRevision.current} available={keshaAvailable} patched={keshaPatched} applyDelay={applyKeshaDelay} undoDelay={undoKeshaDelay} />
          </Grid>
          <Grid item xs={12} sm={6}><TextField fullWidth label="Второй срок, месяцев" type="number" inputProps={{ min: 1, max: 120 }} value={form.alternativeTermMonths || ''} disabled={fieldDisabled} onChange={(event) => change('alternativeTermMonths', Number(event.target.value))} /></Grid>
        </Grid>
        {scenario && <FinancingComparison scenario={scenario} onDownloadSource={(documentId) => agentApi.downloadDocument(id, documentId, 'Счёт.pdf')} />}
        <RadioGroup row aria-label="Выбрать срок для передачи" value={form.selectedTermMonths === form.alternativeTermMonths ? form.alternativeTermMonths : baseTerm}
          onChange={(_, value) => change('selectedTermMonths', Number(value))}>
          <FormControlLabel value={baseTerm} control={<Radio />} disabled={fieldDisabled || !baseTerm} label={months(baseTerm)} />
          {!!form.alternativeTermMonths && form.alternativeTermMonths !== baseTerm &&
            <FormControlLabel value={form.alternativeTermMonths} control={<Radio />} disabled={fieldDisabled} label={months(form.alternativeTermMonths)} />}
        </RadioGroup>
        {(modified || conflictingTerms) && <Typography variant="body2" color="text.secondary">Сохраните новый расчёт перед передачей клиенту.</Typography>}
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1}>
          <Button variant="outlined" disabled={!enabled} onClick={() => void calculate()}>
            {busy === 'calculate' ? 'Считаем…' : 'Рассчитать и сохранить варианты'}
          </Button>
          {modified && <Button disabled={fieldDisabled} onClick={() => {
            setForm(scenario?.inputs ?? defaultInputs(baseTerm));
            setExtraction(null);
            invoiceUpload.current = null;
            setInvoiceDocumentId(null);
            setSelectedSourceDate('');
            setUseSourceAmount(false);
            setSourceManuallyEdited(false);
            invalidateDelayOffer();
            setDirty(false);
            setInvoiceNotice(null);
            setError(null);
          }}>Сбросить правки расчёта</Button>}
        </Stack>
      </Stack>
    </Paper>
  );
}
