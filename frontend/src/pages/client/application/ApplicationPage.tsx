import { Alert, Box, Button, Chip, Dialog, DialogActions, DialogContent, DialogTitle, Paper, Stack, TextField, Typography } from '@mui/material';
import ArrowBackRoundedIcon from '@mui/icons-material/ArrowBackRounded';
import { useCallback, useEffect, useState } from 'react';
import { Link as RouterLink, useParams } from 'react-router-dom';
import { applicationsApi } from '../../../api/client';
import { ErrorState } from '../../../components/ErrorState';
import { LoadingBlock } from '../../../components/LoadingBlock';
import { PageHeader } from '../../../components/PageHeader';
import { explainStatus, SYSTEM_STATUSES, statusTitle, statusTone } from '../../../shared/applicationText';
import { months, rub } from '../../../shared/format';
import { Application } from '../../../types';
import { DocumentsBlock } from './DocumentsBlock';
import { DraftForm } from './DraftForm';
import { HistoryBlock } from './HistoryBlock';
import { InfoRequestBlock } from './InfoRequestBlock';
import { LoanBlock } from './LoanBlock';
import { OfferBlock } from './OfferBlock';
import { SigningBlock } from './SigningBlock';
import { StageStepper } from './StageStepper';

/** While the bank works the card refreshes every 2 seconds; otherwise every 15, to catch an underwriter's decision. */
const FAST_REFRESH_MS = 2000;
const SLOW_REFRESH_MS = 15000;

export function ApplicationPage() {
  const { id = '' } = useParams();
  const [application, setApplication] = useState<Application | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [withdrawing, setWithdrawing] = useState(false);
  const [withdrawComment, setWithdrawComment] = useState('');

  const load = useCallback(async () => {
    try {
      setApplication(await applicationsApi.get(id));
      setError(null);
    } catch (nextError) {
      setError(nextError instanceof Error ? nextError.message : 'Не удалось загрузить заявку');
    }
  }, [id]);

  useEffect(() => {
    void load();
  }, [load]);

  const status = application?.status;
  useEffect(() => {
    if (!status || status === 'DRAFT') return;
    const timer = window.setInterval(() => void load(), SYSTEM_STATUSES.includes(status) ? FAST_REFRESH_MS : SLOW_REFRESH_MS);
    return () => window.clearInterval(timer);
  }, [status, load]);

  if (error && !application) return <ErrorState message={error} onRetry={load} />;
  if (!application) return <LoadingBlock />;

  const explanation = explainStatus(application);
  const tone = statusTone(application.status);
  const outcome = application.process.outcome;
  const canWithdraw = application.allowedActions.includes('WITHDRAW');

  return (
    <>
      <Button component={RouterLink} to="/client" startIcon={<ArrowBackRoundedIcon />} sx={{ mb: 1 }}>Все заявки</Button>
      <PageHeader
        title={`Заявка${application.number ? ` № ${application.number}` : ''}`}
        subtitle={`${application.product.name} · ${rub(application.amount)} · ${months(application.termMonths)}`}
        actions={<Chip label={statusTitle(application.status)} color={tone === 'default' ? 'default' : tone} sx={{ alignSelf: { xs: 'flex-start', md: 'center' } }} />}
      />
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

        {application.status === 'DRAFT' && application.editable && (
          <DraftForm application={application} onSaved={setApplication} onSubmitted={setApplication} />
        )}
        {application.status === 'INFO_REQUESTED' && application.process.infoRequest && (
          <InfoRequestBlock application={application} onChanged={load} onSent={setApplication} />
        )}
        {application.process.offer && ['OFFER_ISSUED', 'SIGNING', 'DISBURSING', 'DISBURSED'].includes(application.status) && (
          <OfferBlock application={application} onChanged={setApplication} />
        )}
        {application.status === 'SIGNING' && <SigningBlock application={application} onChanged={setApplication} />}
        {application.status === 'DISBURSED' && application.process.loan && <LoanBlock application={application} />}
        {outcome && (
          <Paper variant="outlined" sx={{ p: { xs: 2, md: 3 }, borderColor: 'error.main' }}>
            <Typography variant="h6" component="h2">{outcome.reasonTitle ?? 'Банк не может выдать кредит'}</Typography>
            <Stack component="ul" spacing={0.5} sx={{ pl: 2.5, mb: 0 }}>
              {outcome.explanation.flatMap((text) => text.split('\n')).map((line) => <Typography component="li" key={line}>{line}</Typography>)}
            </Stack>
            <Button component={RouterLink} to="/client/new" variant="outlined" sx={{ mt: 2 }}>Подобрать другой вариант</Button>
          </Paper>
        )}

        <DocumentsBlock application={application} onChanged={load} />
        <HistoryBlock applicationId={application.id} version={`${application.status}-${application.documents.length}`} />

        {canWithdraw && (
          <Box>
            <Button color="error" onClick={() => setWithdrawing(true)}>Отозвать заявку</Button>
          </Box>
        )}
      </Stack>

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
                setApplication(await applicationsApi.withdraw(application.id, withdrawComment.trim() || undefined));
                setWithdrawing(false);
              } catch (nextError) {
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
