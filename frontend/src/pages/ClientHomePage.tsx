import { Box, Button, Card, CardActionArea, Chip, Grid, Paper, Stack, Typography } from '@mui/material';
import AddRoundedIcon from '@mui/icons-material/AddRounded';
import ChevronRightRoundedIcon from '@mui/icons-material/ChevronRightRounded';
import { Link as RouterLink, useNavigate } from 'react-router-dom';
import { applicationsApi, companyApi } from '../api/client';
import { EmptyState } from '../components/EmptyState';
import { ErrorState } from '../components/ErrorState';
import { LoadingBlock } from '../components/LoadingBlock';
import { PageHeader } from '../components/PageHeader';
import { useApi } from '../hooks/useApi';
import { FINISHED_STATUSES, statusTitle, statusTone } from '../shared/applicationText';
import { formatDateTime, months, rub } from '../shared/format';
import { legalFormLabels } from '../shared/labels';
import { tokens } from '../theme/theme';
import { ApplicationSummary } from '../types';

function ApplicationRow({ item }: { item: ApplicationSummary }) {
  const tone = statusTone(item.status);
  return (
    <Card>
      <CardActionArea component={RouterLink} to={`/client/applications/${item.id}`} sx={{ p: { xs: 2, md: 3 } }}>
        <Stack direction="row" spacing={2} alignItems="center">
          <Box sx={{ flexGrow: 1, minWidth: 0 }}>
            <Typography variant="body2" color="text.secondary">№ {item.number} · обновлена {formatDateTime(item.statusChangedAt)}</Typography>
            <Typography fontWeight={700} sx={{ mt: 0.5 }}>{item.productName}</Typography>
            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={{ xs: 1, sm: 2 }} alignItems={{ sm: 'center' }} sx={{ mt: 1 }}>
              <Typography variant="subtitle1" component="p" fontWeight={700}>{rub(item.amount)} <Typography component="span" color="text.secondary">· {months(item.termMonths)}</Typography></Typography>
              <Chip label={statusTitle(item.status)} color={tone === 'default' ? 'default' : tone} sx={{ alignSelf: { xs: 'flex-start', sm: 'center' } }} />
            </Stack>
          </Box>
          <ChevronRightRoundedIcon aria-hidden sx={{ color: 'text.secondary' }} />
        </Stack>
      </CardActionArea>
    </Card>
  );
}

export function ClientHomePage() {
  const navigate = useNavigate();
  const company = useApi(companyApi.mine);
  const applications = useApi(applicationsApi.list);

  if (company.loading || applications.loading) return <LoadingBlock />;
  if (company.error || !company.data) return <ErrorState message={company.error ?? 'Компания не найдена'} onRetry={company.reload} />;
  if (applications.error || !applications.data) return <ErrorState message={applications.error ?? 'Нет данных'} onRetry={applications.reload} />;

  const active = applications.data.filter((item) => !FINISHED_STATUSES.includes(item.status));
  const finished = applications.data.filter((item) => FINISHED_STATUSES.includes(item.status));
  const waitingForClient = active.filter((item) => statusTone(item.status) === 'warning');

  return (
    <>
      <PageHeader
        title={company.data.name ?? `ИНН ${company.data.inn}`}
        subtitle={`${legalFormLabels[company.data.legalForm]} · ИНН ${company.data.inn}`}
        actions={<Button component={RouterLink} to="/client/new" variant="contained" size="large" startIcon={<AddRoundedIcon />}>Подобрать финансирование</Button>}
      />
      <Grid container spacing={3}>
        <Grid item xs={12} md={8}>
          <Stack spacing={3}>
            {waitingForClient.length > 0 && (
              <Paper variant="outlined" sx={{ p: { xs: 2, md: 3 }, borderColor: 'warning.main' }}>
                <Typography variant="h6" component="h2">Нужно ваше действие</Typography>
                <Stack spacing={0.5} sx={{ mt: 1 }}>
                  {waitingForClient.map((item) => (
                    <Typography key={item.id} component={RouterLink} to={`/client/applications/${item.id}`} sx={{ color: 'inherit' }}>
                      № {item.number} — {statusTitle(item.status).toLowerCase()}
                    </Typography>
                  ))}
                </Stack>
              </Paper>
            )}

            {applications.data.length === 0 ? (
              <EmptyState
                title="Заявок пока нет"
                description="Расскажите, на что нужны деньги, — подберём продукт, посчитаем платёж и поможем подать заявку."
                actionLabel="Подобрать финансирование"
                onAction={() => navigate('/client/new')}
              />
            ) : (
              ([['В работе', active], ['Завершённые', finished]] as const).map(([title, items]) =>
                items.length > 0 && (
                  <Box key={title}>
                    <Typography variant="h6" component="h2" sx={{ mb: 1.5 }}>{title}</Typography>
                    <Stack spacing={1.5}>
                      {items.map((item) => <ApplicationRow key={item.id} item={item} />)}
                    </Stack>
                  </Box>
                )
              )
            )}
          </Stack>
        </Grid>

        <Grid item xs={12} md={4}>
          <Stack spacing={3} sx={{ position: { md: 'sticky' }, top: { md: 96 } }}>
            <Paper variant="outlined" sx={{ p: { xs: 2, md: 3 } }}>
              <Typography variant="subtitle1" component="h2" fontWeight={700}>Представители компании</Typography>
              <Stack spacing={1.5} sx={{ mt: 1.5 }}>
                {company.data.members.map((member) => (
                  <Stack key={member.id} direction="row" spacing={1.5} alignItems="center">
                    <Box aria-hidden sx={{ width: 40, height: 40, flexShrink: 0, borderRadius: '99px', bgcolor: tokens.secondaryFill, display: 'grid', placeItems: 'center', fontWeight: 700 }}>
                      {member.fullName.charAt(0)}
                    </Box>
                    <Box>
                      <Typography>{member.fullName}</Typography>
                      {member.canSign && <Typography variant="body2" color="text.secondary">право подписи</Typography>}
                    </Box>
                  </Stack>
                ))}
              </Stack>
            </Paper>
            <Paper sx={{ p: { xs: 2, md: 3 }, bgcolor: tokens.dark, color: '#fff' }}>
              <Typography variant="subtitle1" component="h2" fontWeight={700}>Нужны деньги на бизнес?</Typography>
              <Typography sx={{ mt: 1, color: 'rgba(255,255,255,0.72)' }}>Подберём продукт под задачу и сразу покажем платёж.</Typography>
              <Button component={RouterLink} to="/client/new" variant="contained" sx={{ mt: 2 }}>Подобрать</Button>
            </Paper>
          </Stack>
        </Grid>
      </Grid>
    </>
  );
}
