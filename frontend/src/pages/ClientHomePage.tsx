import { Paper, Stack, Typography } from '@mui/material';
import { companyApi } from '../api/client';
import { EmptyState } from '../components/EmptyState';
import { ErrorState } from '../components/ErrorState';
import { LoadingBlock } from '../components/LoadingBlock';
import { PageHeader } from '../components/PageHeader';
import { useApi } from '../hooks/useApi';
import { legalFormLabels } from '../shared/labels';

export function ClientHomePage() {
  const { data: company, loading, error, reload } = useApi(companyApi.mine);

  if (loading) return <LoadingBlock />;
  if (error || !company) return <ErrorState message={error ?? 'Компания не найдена'} onRetry={reload} />;

  return (
    <>
      <PageHeader title={company.name ?? `ИНН ${company.inn}`} subtitle={legalFormLabels[company.legalForm]} />
      <Stack spacing={3}>
        <Paper variant="outlined" sx={{ p: 3 }}>
          <Typography variant="subtitle2" color="text.secondary">ИНН</Typography>
          <Typography variant="h6">{company.inn}</Typography>
          <Typography variant="subtitle2" color="text.secondary" sx={{ mt: 2 }}>Представители в кабинете</Typography>
          {company.members.map((member) => (
            <Typography key={member.id}>{member.fullName}{member.canSign ? ' — право подписи' : ''}</Typography>
          ))}
        </Paper>
        <EmptyState title="Заявок пока нет" description="Здесь появятся ваши заявки на финансирование и их статус." />
      </Stack>
    </>
  );
}
