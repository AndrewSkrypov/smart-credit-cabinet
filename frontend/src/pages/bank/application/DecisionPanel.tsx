import { Alert, Box, Button, Checkbox, Dialog, DialogActions, DialogContent, DialogTitle, FormControlLabel, FormGroup, MenuItem, Paper, Stack, TextField, Typography } from '@mui/material';
import { useState } from 'react';
import { productsApi, underwritingApi } from '../../../api/client';
import { MoneyField } from '../../../components/MoneyField';
import { useApi } from '../../../hooks/useApi';
import { months, percent, rub, rubExact } from '../../../shared/format';
import { Application, AssessmentView, DocumentType, UnderwritingAction } from '../../../types';

export const outcomeTitles: Record<string, string> = {
  APPROVE: 'Одобрить на запрошенных условиях',
  COUNTER_OFFER: 'Предложить другие условия',
  REJECT: 'Отказать',
  REQUEST_INFO: 'Запросить данные',
  MANUAL_REVIEW: 'Решение за специалистом'
};

const requestableDocuments: Array<{ type: DocumentType; title: string }> = [
  { type: 'TAX_RETURN', title: 'Налоговая декларация' },
  { type: 'FINANCIAL_STATEMENTS', title: 'Бухгалтерская отчётность' },
  { type: 'BANK_STATEMENT', title: 'Выписка по счёту в другом банке' },
  { type: 'EQUIPMENT_INVOICE', title: 'Счёт или спецификация на оборудование' },
  { type: 'OTHER', title: 'Другой документ' }
];

/**
 * What the rules propose and the underwriter's actions. Going against the recommendation needs a
 * reason: the dialog says so before sending, the server checks it again.
 */
export function DecisionPanel({ application, assessment, onDone }: { application: Application; assessment: AssessmentView; onDone: () => void }) {
  const recommendation = assessment.automaticDecision?.recommendation ?? null;
  const reasons = assessment.automaticDecision?.manualReasons ?? [];
  const [dialog, setDialog] = useState<UnderwritingAction | null>(null);
  if (assessment.actions.length === 0) return null;

  return (
    <Paper variant="outlined" sx={{ p: { xs: 2, md: 3 }, borderColor: 'warning.main' }}>
      <Typography variant="h6" component="h2">Решение по заявке</Typography>
      {reasons.length > 0 && (
        <Typography color="text.secondary" sx={{ mb: 1 }}>Почему на ручном рассмотрении: {reasons.map((reason) => reason.title.toLowerCase()).join('; ')}.</Typography>
      )}
      <Box sx={{ p: 2, my: 2, borderRadius: 2, bgcolor: 'action.hover' }}>
        <Typography variant="body2" color="text.secondary">Рекомендация системы</Typography>
        {recommendation ? (
          <>
            <Typography fontWeight={700}>{outcomeTitles[recommendation.outcome]}</Typography>
            {recommendation.terms && (
              <Typography>
                {rub(recommendation.terms.amount)} · {months(recommendation.terms.termMonths)} · {percent(recommendation.terms.rate)} · платёж {rubExact(recommendation.terms.monthlyPayment)}
              </Typography>
            )}
            {recommendation.notes.map((note) => <Typography key={note} variant="body2" color="text.secondary">{note}</Typography>)}
          </>
        ) : (
          <Typography>Нет: оценка не проводилась, потому что источник данных не ответил. Любое решение — с обоснованием.</Typography>
        )}
      </Box>
      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5} flexWrap="wrap" useFlexGap>
        {assessment.actions.includes('APPROVE') && <Button variant="contained" onClick={() => setDialog('APPROVE')}>Одобрить</Button>}
        {assessment.actions.includes('COUNTER_OFFER') && <Button variant="outlined" onClick={() => setDialog('COUNTER_OFFER')}>Другие условия</Button>}
        {assessment.actions.includes('REQUEST_INFO') && <Button variant="outlined" onClick={() => setDialog('REQUEST_INFO')}>Запросить данные</Button>}
        {assessment.actions.includes('REJECT') && <Button variant="outlined" color="error" onClick={() => setDialog('REJECT')}>Отказать</Button>}
      </Stack>
      {dialog && (
        <ActionDialog
          action={dialog}
          application={application}
          recommendationOutcome={recommendation?.outcome ?? null}
          recommended={recommendation?.terms ?? null}
          onClose={() => setDialog(null)}
          onDone={() => {
            setDialog(null);
            onDone();
          }}
        />
      )}
    </Paper>
  );
}

interface ActionDialogProps {
  action: UnderwritingAction;
  application: Application;
  recommendationOutcome: string | null;
  recommended: { productCode: string; amount: number; termMonths: number } | null;
  onClose: () => void;
  onDone: () => void;
}

function ActionDialog({ action, application, recommendationOutcome, recommended, onClose, onDone }: ActionDialogProps) {
  const products = useApi(productsApi.list);
  const [comment, setComment] = useState('');
  const [productCode, setProductCode] = useState(recommended?.productCode ?? application.product.code);
  const [amount, setAmount] = useState<number | null>(recommended?.amount ?? application.amount);
  const [termMonths, setTermMonths] = useState(recommended?.termMonths ?? application.termMonths);
  const [documents, setDocuments] = useState<DocumentType[]>([]);
  const [question, setQuestion] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const differsFromRecommendation =
    action === 'REJECT' ||
    (action === 'APPROVE' && recommendationOutcome !== 'APPROVE') ||
    (action === 'COUNTER_OFFER' && !(recommended && recommended.productCode === productCode && recommended.amount === amount && recommended.termMonths === termMonths));
  const commentRequired = action !== 'REQUEST_INFO' && differsFromRecommendation;
  const product = products.data?.find((item) => item.code === productCode);

  const send = async () => {
    setBusy(true);
    setError(null);
    try {
      const text = comment.trim() || undefined;
      if (action === 'APPROVE') await underwritingApi.approve(application.id, text);
      if (action === 'COUNTER_OFFER') await underwritingApi.counterOffer(application.id, { productCode, amount: amount ?? 0, termMonths, comment: text });
      if (action === 'REJECT') await underwritingApi.reject(application.id, comment.trim());
      if (action === 'REQUEST_INFO') {
        const items = [...documents.map((documentType) => ({ documentType })), ...(question.trim() ? [{ question: question.trim() }] : [])];
        await underwritingApi.requestInfo(application.id, items, text);
      }
      onDone();
    } catch (nextError) {
      setError(nextError instanceof Error ? nextError.message : 'Не удалось сохранить решение');
      setBusy(false);
    }
  };

  const ready =
    (!commentRequired || comment.trim().length > 0) &&
    (action !== 'REQUEST_INFO' || documents.length > 0 || question.trim().length > 0) &&
    (action !== 'COUNTER_OFFER' || !!amount);

  const titles: Record<UnderwritingAction, string> = {
    APPROVE: 'Одобрить заявку',
    COUNTER_OFFER: 'Предложить другие условия',
    REQUEST_INFO: 'Запросить данные у клиента',
    REJECT: 'Отказать'
  };

  return (
    <Dialog open onClose={onClose} fullWidth maxWidth="sm">
      <DialogTitle>{titles[action]}</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ pt: 1 }}>
          {action === 'APPROVE' && (
            <Typography>Клиент получит предложение: {rub(application.amount)} на {months(application.termMonths)} по ставке для рейтинга заявки.</Typography>
          )}
          {action === 'COUNTER_OFFER' && (
            <>
              <TextField select label="Продукт" value={productCode} onChange={(event) => setProductCode(event.target.value)}>
                {(products.data ?? []).map((item) => <MenuItem key={item.code} value={item.code}>{item.name}</MenuItem>)}
              </TextField>
              <MoneyField label="Сумма" value={amount} onChange={setAmount} helperText={product ? `от ${rub(product.minAmount)} до ${rub(product.maxAmount)}` : ' '} />
              <TextField label="Срок, месяцев" inputProps={{ inputMode: 'numeric' }} value={termMonths || ''} onChange={(event) => setTermMonths(Number(event.target.value.replace(/\D/g, '').slice(0, 3)) || 0)}
                helperText={product ? `от ${product.minTermMonths} до ${product.maxTermMonths}` : ' '} />
            </>
          )}
          {action === 'REQUEST_INFO' && (
            <>
              <FormGroup>
                {requestableDocuments.map((item) => (
                  <FormControlLabel
                    key={item.type}
                    label={item.title}
                    control={<Checkbox checked={documents.includes(item.type)} onChange={(event) => setDocuments(event.target.checked ? [...documents, item.type] : documents.filter((type) => type !== item.type))} />}
                  />
                ))}
              </FormGroup>
              <TextField label="Вопрос клиенту" multiline minRows={2} value={question} onChange={(event) => setQuestion(event.target.value)} inputProps={{ maxLength: 500 }} />
            </>
          )}
          <TextField
            label={action === 'REJECT' ? 'Причина отказа — её увидит клиент' : commentRequired ? 'Обоснование (обязательно: решение расходится с рекомендацией)' : 'Комментарий'}
            multiline
            minRows={3}
            value={comment}
            onChange={(event) => setComment(event.target.value)}
            inputProps={{ maxLength: 2000 }}
            required={commentRequired}
          />
          {error && <Alert severity="error">{error}</Alert>}
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Отмена</Button>
        <Button variant="contained" color={action === 'REJECT' ? 'error' : 'primary'} disabled={!ready || busy} onClick={send}>Сохранить решение</Button>
      </DialogActions>
    </Dialog>
  );
}
