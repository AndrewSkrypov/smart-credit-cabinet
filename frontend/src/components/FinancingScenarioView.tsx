import { Alert, Box, Button, Chip, Paper, Stack, Table, TableBody, TableCell, TableHead, TableRow, Typography } from '@mui/material';
import { useState } from 'react';
import { applicationsApi } from '../api/client';
import { FinancingPreview, FinancingScenario } from '../api/financing';
import { formatDate, months, percent, rub, rubExact } from '../shared/format';
import { TableScrollRegion } from './TableScrollRegion';

interface ComparisonProps {
  scenario: Pick<FinancingScenario, 'inputs' | 'loanAmount' | 'annualRatePercent' | 'options'>;
  onDownloadSource?: (documentId: string) => Promise<void>;
  exact?: boolean;
  paymentLabel?: string;
}

export function FinancingComparison({ scenario, onDownloadSource, exact = false, paymentLabel = 'Платёж в месяц' }: ComparisonProps) {
  const { inputs } = scenario;
  const formatResult = exact ? rubExact : rub;
  const sourceDocumentId = inputs.source?.documentId;
  const [downloadError, setDownloadError] = useState<string | null>(null);

  async function download(documentId: string) {
    setDownloadError(null);
    try {
      await onDownloadSource?.(documentId);
    } catch (error) {
      setDownloadError(error instanceof Error ? error.message : 'Счёт недоступен');
    }
  }

  return (
    <Stack spacing={1.5}>
      <Typography variant="body2">
        {rub(scenario.loanAmount)} · {percent(scenario.annualRatePercent)} годовых · Платёж {formatDate(inputs.paymentDate)}
      </Typography>
      <Typography variant="body2" color="text.secondary">
        Остаток до поступления {rub(inputs.openingBalance)}. Ожидается {rub(inputs.expectedReceiptAmount)} к {formatDate(inputs.expectedReceiptDate)}. Задержка {inputs.delayDays} дн.
      </Typography>
      <TableScrollRegion label="Сравнение сценариев финансирования">
        <Table size="small" aria-label="Сравнение сценариев финансирования">
          <TableHead>
            <TableRow>
              <TableCell>Срок</TableCell>
              <TableCell align="right">{paymentLabel}</TableCell>
              <TableCell align="right">Остаток после платежа</TableCell>
              <TableCell align="right">Проценты за весь срок</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {scenario.options.map((option) => (
              <TableRow key={option.termMonths} selected={option.termMonths === inputs.selectedTermMonths}>
                <TableCell component="th" scope="row">
                  {months(option.termMonths)}
                  {option.termMonths === inputs.selectedTermMonths && <Chip size="small" label="Выбран" sx={{ ml: 1 }} />}
                </TableCell>
                <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>{formatResult(option.monthlyPayment)}</TableCell>
                <TableCell align="right" sx={{ color: option.balanceOnPaymentDate < 0 ? 'error.main' : 'text.primary', fontWeight: 600, whiteSpace: 'nowrap' }}>
                  {formatResult(option.balanceOnPaymentDate)}
                </TableCell>
                <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>{formatResult(option.totalInterest)}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </TableScrollRegion>
      {inputs.source && (
        <Box>
          <Typography variant="body2" fontWeight={600}>Счёт · страница {inputs.source.page}</Typography>
          <Typography variant="body2" color="text.secondary" component="blockquote" sx={{ m: 0, overflowWrap: 'anywhere' }}>{inputs.source.amountQuote}</Typography>
          <Typography variant="body2" color="text.secondary" component="blockquote" sx={{ m: 0, overflowWrap: 'anywhere' }}>{inputs.source.dateQuote}</Typography>
          {sourceDocumentId && onDownloadSource && (
            <Button size="small" onClick={() => void download(sourceDocumentId)}>Скачать исходный счёт</Button>
          )}
          {downloadError && <Alert severity="warning">{downloadError}</Alert>}
        </Box>
      )}
    </Stack>
  );
}

export function FinancingPreviewComparison({ preview, selectedTermMonths }: { preview: FinancingPreview; selectedTermMonths: number }) {
  const { inputsUsed } = preview;
  const scenario = {
    inputs: {
      openingBalance: Number(inputsUsed.openingBalanceRub),
      expectedReceiptAmount: Number(inputsUsed.expectedReceiptAmountRub),
      expectedReceiptDate: inputsUsed.expectedReceiptDate,
      paymentDate: inputsUsed.paymentDate,
      delayDays: inputsUsed.delayDays,
      alternativeTermMonths: inputsUsed.alternativeTermMonths,
      selectedTermMonths
    },
    loanAmount: Number(inputsUsed.loanAmountRub),
    annualRatePercent: Number(inputsUsed.annualRatePercent),
    options: preview.options.map((option) => ({
      termMonths: option.termMonths,
      monthlyPayment: Number(option.monthlyPaymentRub),
      totalInterest: Number(option.totalInterestRub),
      balanceOnPaymentDate: Number(option.balanceOnPaymentDateRub)
    }))
  };
  const deficits = scenario.options.filter((option) => option.balanceOnPaymentDate < 0);
  const hasExpectedReceipt = scenario.inputs.expectedReceiptAmount !== 0;

  return (
    <Stack spacing={1.5} role="region" aria-label="Предварительный расчёт">
      <Typography variant="subtitle1" fontWeight={600}>Предварительный расчёт</Typography>
      <Typography variant="body2">
        {hasExpectedReceipt ? (
          <>
            {inputsUsed.receiptIncludedByPayment
              ? 'Поступление учтено в остатке на дату платежа.'
              : 'Поступление ожидается после даты платежа. Остаток рассчитан до зачисления.'}
            {' '}{inputsUsed.lastIncludedDelayDays === null
              ? 'Поступление ожидается после платежа даже без задержки.'
              : `Поступление успевает к платежу при задержке до ${inputsUsed.lastIncludedDelayDays} дн. включительно.`}
          </>
        ) : 'Ожидаемое поступление равно нулю. Задержка не меняет остаток.'}
      </Typography>
      <FinancingComparison
        scenario={scenario}
        exact
        paymentLabel={inputsUsed.paymentModel === 'REVOLVING' ? 'Первый минимальный платёж' : undefined}
      />
      {deficits.map((option) => (
        <Typography key={option.termMonths} variant="body2" color="error.main">
          При сроке {months(option.termMonths)} на дату платежа не хватает {rubExact(-option.balanceOnPaymentDate)}.
        </Typography>
      ))}
    </Stack>
  );
}

export function FinancingScenarioView({ applicationId, scenario }: { applicationId: string; scenario: FinancingScenario }) {
  const label = scenario.status === 'CONFIRMED' ? 'Подтверждённый пакет' : 'Пакет на подтверждение';
  return (
    <Paper variant="outlined" sx={{ p: { xs: 2, md: 3 } }}>
      <Stack spacing={1.5}>
        <Typography variant="h6" component="h2">{label} · версия {scenario.revision}</Typography>
        {scenario.changes?.length ? (
          <Alert severity="info">
            Изменения после предыдущей передачи
            <Box component="ul" sx={{ my: 0, pl: 2 }}>
              {scenario.changes.map((change, index) => <li key={index}>{change}</li>)}
            </Box>
          </Alert>
        ) : null}
        <FinancingComparison scenario={scenario} onDownloadSource={(documentId) => applicationsApi.downloadDocument(applicationId, documentId, 'Счёт.pdf')} />
      </Stack>
    </Paper>
  );
}
