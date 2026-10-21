import { EmptyState } from '../components/EmptyState';
import { PageHeader } from '../components/PageHeader';

export function BankHomePage() {
  return (
    <>
      <PageHeader title="Заявки" subtitle="Очередь заявок малого и микробизнеса" />
      <EmptyState title="Заявок в работе нет" description="Новые заявки появятся здесь, как только клиенты их отправят." />
    </>
  );
}
