import { Box, Stack, Table, TableBody, TableCell, TableHead, TableRow, Typography } from '@mui/material';
import { months, percent, rub } from '../shared/format';
import { FinancingPurpose, Product, RuleParams } from '../types';

const number = new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 4 });
function Facts({ rows }: { rows: Array<[string, string]> }) {
  return <Box component="dl" sx={{ m: 0, display: 'grid', gridTemplateColumns: { xs: '1fr', sm: 'minmax(0, 1fr) minmax(0, 1fr)' }, gap: '4px 24px', overflowWrap: 'anywhere' }}>
    {rows.map(([label, value]) => <Box key={label} sx={{ display: 'contents' }}><Typography component="dt" color="text.secondary">{label}</Typography><Typography component="dd" sx={{ m: 0, mb: 1.5 }}>{value}</Typography></Box>)}
  </Box>;
}

export function ProductSnapshot({ product: p, purposes }: { product: Product; purposes: { code: FinancingPurpose; title: string }[] }) {
  return <Stack spacing={2} sx={{ overflowWrap: 'anywhere' }}>
    <Typography sx={{ whiteSpace: 'pre-wrap' }}>{p.description}</Typography>
    <Facts rows={[
      ['Базовая ставка', percent(p.baseRate)],
      ['Сумма', p.minAmount === p.maxAmount ? rub(p.minAmount) : `${rub(p.minAmount)} — ${rub(p.maxAmount)}`],
      ['Срок', p.minTermMonths === p.maxTermMonths ? months(p.minTermMonths) : `${months(p.minTermMonths)} — ${months(p.maxTermMonths)}`],
      ['Лимит автоодобрения', rub(p.autoApprovalLimit)],
      ['Тип лимита', p.revolving ? 'Возобновляемый лимит' : 'Разовая выдача'],
      ['Доступность', p.active ? 'В каталоге' : 'Выключен']
    ]} />
    <Typography variant="h6" component="h3">Цели финансирования</Typography>
    <Typography>{p.purposes.map((code) => purposes.find((item) => item.code === code)?.title ?? code).join('; ') || 'Не заданы'}</Typography>
  </Stack>;
}

export function RuleSnapshot({ params: p }: { params: RuleParams }) {
  const fraction = (v: number) => `${number.format(v * 100)}%`;
  return <Stack spacing={2.5}>
    <Typography variant="h6" component="h3">Нагрузка и выручка</Typography>
    <Facts rows={[
      ['Предельная долговая нагрузка', fraction(p.maxLoad)],
      ['Допустимое расхождение выручки', fraction(p.revenueMismatch)],
      ['Рейтинг D: доля от запроса', fraction(p.weakAmountShare)],
      ['Рейтинг D: доля годовой выручки', fraction(p.weakRevenueShare)]
    ]} />
    <Table size="small" aria-label="Пороги и надбавки рейтингов" sx={{ tableLayout: 'fixed', '& th, & td': { px: { xs: 0.5, sm: 2 }, overflowWrap: 'anywhere' } }}>
      <TableHead><TableRow><TableCell>Рейтинг</TableCell><TableCell>От баллов</TableCell><TableCell>Надбавка, п.п.</TableCell></TableRow></TableHead>
      <TableBody>{(['A', 'B', 'C', 'D'] as const).map((r) => <TableRow key={r}><TableCell component="th" scope="row">{r}</TableCell><TableCell>{number.format(p.ratingCutoffs[r])}</TableCell><TableCell>{number.format(p.ratingMargins[r])}</TableCell></TableRow>)}</TableBody>
    </Table>
    <Typography variant="h6" component="h3">Отраслевой риск</Typography>
    <Facts rows={[
      ['Низкий риск (ОКВЭД)', p.lowRiskOkved.join(', ') || 'Не задан'],
      ['Высокий риск (ОКВЭД)', p.highRiskOkved.join(', ') || 'Не задан']
    ]} />
  </Stack>;
}
