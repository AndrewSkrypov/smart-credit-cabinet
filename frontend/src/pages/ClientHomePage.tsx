import { Box, Button, Card, CardActionArea, Chip, Paper, Stack, Typography } from '@mui/material';
import AddRoundedIcon from '@mui/icons-material/AddRounded';
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
      <Stack spacing={3}>
        {waitingForClient.length > 0 && (
          <Paper variant="outlined" sx={{ p: { xs: 2, md: 3 }, borderColor: 'warning.main' }}>
            <Typography variant="h6" component="h2">Нужно ваше действие</Typography>
            <Typography color="text.secondary">
              {waitingForClient.map((item) => `№ ${item.number} — ${statusTitle(item.status).toLowerCase()}`).join('; ')}
            </Typography>
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
          <>
            {[['В работе', active], ['Завершённые', finished]].map(([title, items]) =>
              (items as typeof active).length > 0 && (
                <Box key={title as string}>
                  <Typography variant="h6" component="h2" sx={{ mb: 1.5 }}>{title as string}</Typography>
                  <Stack spacing={1.5}>
                    {(items as typeof active).map((item) => {
                      const tone = statusTone(item.status);
                      return (
                        <Card key={item.id} variant="outlined">
                          <CardActionArea component={RouterLink} to={`/client/applications/${item.id}`} sx={{ p: { xs: 2, md: 2.5 } }}>
                            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5} justifyContent="space-between" alignItems={{ sm: 'center' }}>
                              <Box>
                                <Typography fontWeight={700}>№ {item.number} · {item.productName}</Typography>
                                <Typography variant="body2" color="text.secondary">
                                  {rub(item.amount)} · {months(item.termMonths)} · обновлена {formatDateTime(item.statusChangedAt)}
                                </Typography>
                              </Box>
                              <Chip label={statusTitle(item.status)} color={tone === 'default' ? 'default' : tone} />
                            </Stack>
                          </CardActionArea>
                        </Card>
                      );
                    })}
                  </Stack>
                </Box>
              )
            )}
          </>
        )}

        <Paper variant="outlined" sx={{ p: { xs: 2, md: 3 } }}>
          <Typography variant="subtitle1" fontWeight={700}>Представители компании</Typography>
          {company.data.members.map((member) => (
            <Typography key={member.id} color="text.secondary">{member.fullName}{member.canSign ? ' — право подписи' : ''}</Typography>
          ))}
        </Paper>
      </Stack>
    </>
  );
}
