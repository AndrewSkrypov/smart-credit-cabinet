import ChevronRightRoundedIcon from '@mui/icons-material/ChevronRightRounded';
import ExpandMoreIcon from '@mui/icons-material/ExpandMore';
import { Accordion, AccordionDetails, AccordionSummary, Box, Button, ButtonBase, Container, Grid, Skeleton, Stack, Typography } from '@mui/material';
import { Link as RouterLink } from 'react-router-dom';
import { productsApi } from '../../api/client';
import { roleHome, useAuth } from '../../auth/AuthProvider';
import { BrandMark } from '../../components/BrandMark';
import { Illustration } from '../../components/Illustration';
import { useApi } from '../../hooks/useApi';
import { usePageTitle } from '../../hooks/usePageTitle';
import { months, percent, rub } from '../../shared/format';
import { financingPath } from '../../shared/financingSelection';
import { productArt, purposeArt } from '../../shared/illustrations';
import { fontDisplay, nightGlow, tokens } from '../../theme/theme';
import { FinancingPurpose, Product } from '../../types';
import { LandingCalculator } from './LandingCalculator';
import { ErrorState } from '../../components/ErrorState';

const purposeText: Record<FinancingPurpose, string> = {
  CASH_GAP: 'Платежи проходят, даже когда деньги от клиентов ещё в пути',
  CURRENT_EXPENSES: 'Аренда, зарплаты и налоги без задержек',
  WORKING_CAPITAL: 'Закупки товара и сырья, подготовка к сезону',
  EQUIPMENT: 'Станки, техника и оборудование для производства',
  VEHICLE: 'Грузовой и легковой транспорт для работы',
  RENOVATION: 'Ремонт и обустройство помещения',
  BUSINESS_DEVELOPMENT: 'Новая точка, направление или продукт'
};

const steps = [
  { art: 'cart', title: 'Подберите продукт', text: 'Ответьте, на что нужны деньги, — кабинет предложит подходящий кредит и посчитает платёж.' },
  { art: 'documents', title: 'Заполните заявку', text: 'Реквизиты компании подтянутся из реестра по ИНН. Черновик сохраняется сам.' },
  { art: 'stopwatch', title: 'Дождитесь решения', text: 'Банк сам запросит выписки из реестров, налоговой и бюро. Статус виден на каждом шаге.' },
  { art: 'pen', title: 'Подпишите онлайн', text: 'Примите предложение и подпишите договор простой электронной подписью — без визита в офис.' }
];

const faq = [
  { q: 'Кто может подать заявку?', a: 'Индивидуальные предприниматели и компании малого бизнеса, зарегистрированные в России. Нужен ИНН и данные о выручке за год.' },
  { q: 'Какие документы понадобятся?', a: 'Чаще всего никаких: банк сам получает сведения из реестра, налоговой и бюро кредитных историй. Если данных не хватит, банк попросит загрузить конкретный документ прямо в кабинете.' },
  { q: 'Что будет, если банк не одобрит всю сумму?', a: 'Банк может сделать контрпредложение — меньшую сумму или другой срок. Его можно принять или отклонить.' },
  { q: 'Как подписать договор?', a: 'Простой электронной подписью: кабинет покажет код подтверждения, его нужно ввести в окне подписания.' }
];

const sectionTitle = { fontFamily: fontDisplay, fontSize: { xs: 28, md: 44 }, fontWeight: 800, letterSpacing: '-0.02em', lineHeight: 1.1 } as const;

/** A bright rounded tile of the dark first screen, as on the bank's business pages. */
function PromoTile({ bg, color = tokens.ink, title, text, art, to, minHeight, artSize = 120, align = 'left' }: {
  bg: string; color?: string; title: string; text?: string; art: string; to: string; minHeight: number | Record<string, number>; artSize?: number; align?: 'left' | 'center';
}) {
  return (
    <ButtonBase component={RouterLink} to={to} focusRipple
      sx={{ display: 'flex', flexDirection: 'column', alignItems: align === 'center' ? 'center' : 'stretch', justifyContent: 'space-between', width: '100%', height: '100%', minHeight,
        p: { xs: 2.5, md: 3.5 }, borderRadius: '32px', bgcolor: bg, color, textAlign: align, overflow: 'hidden', position: 'relative',
        transition: 'transform 0.2s ease', '&:hover': { transform: 'translateY(-2px)' } }}>
      <Box sx={{ position: 'relative', zIndex: 1, maxWidth: align === 'center' ? '100%' : '68%' }}>
        <Typography component="p" fontWeight={700} sx={{ fontSize: { xs: 20, md: 26 }, lineHeight: 1.2 }}>{title}</Typography>
        {text && <Typography sx={{ mt: 1, opacity: 0.85 }}>{text}</Typography>}
      </Box>
      <Illustration name={art} size={artSize}
        sx={align === 'center' ? { mt: 2 } : { position: 'absolute', right: { xs: 12, md: 20 }, bottom: { xs: 12, md: 16 } }} />
    </ButtonBase>
  );
}

function ProductTile({ product, to }: { product: Product | null; to: string }) {
  return (
    <ButtonBase component={RouterLink} to={to} focusRipple disabled={!product}
      sx={{ display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'space-between', width: '100%', height: '100%', minHeight: { xs: 200, md: 300 },
        p: 3, borderRadius: '32px', bgcolor: tokens.tile, color: tokens.ink, textAlign: 'center', transition: 'background-color 0.2s ease', '&:hover': { bgcolor: '#e9eaec' } }}>
      {product ? (
        <>
          <Box>
            <Typography component="h3" fontWeight={700} sx={{ fontSize: { xs: 20, md: 22 }, lineHeight: 1.25 }}>{product.name}</Typography>
            <Typography color="text.secondary" sx={{ mt: 1 }}>до {rub(product.maxAmount)} · от {percent(product.baseRate)}</Typography>
            <Typography variant="body2" color="text.secondary">
              срок {product.minTermMonths === product.maxTermMonths ? months(product.maxTermMonths) : `до ${months(product.maxTermMonths)}`}
            </Typography>
          </Box>
          <Illustration name={productArt[product.code] ?? 'coin'} size={{ xs: 96, md: 150 }} sx={{ mt: 2 }} />
        </>
      ) : <Skeleton variant="rounded" width="100%" height={220} />}
    </ButtonBase>
  );
}

const previewStages = [
  { label: 'Заявка', state: 'done' },
  { label: 'Проверка', state: 'done' },
  { label: 'Оценка', state: 'now' },
  { label: 'Решение', state: 'next' }
] as const;

/** What the client sees after sending an application: the stage, what happens now, the assistant. */
function CabinetPreview() {
  return (
    <Box aria-hidden sx={{ width: 300, p: 2.5, borderRadius: '24px', bgcolor: 'rgba(255,255,255,0.86)', backdropFilter: 'blur(16px)',
      border: '1px solid rgba(255,255,255,0.9)', boxShadow: '0 16px 48px rgba(3,3,6,0.18)', color: tokens.ink }}>
      <Stack direction="row" justifyContent="space-between" alignItems="center">
        <Typography variant="body2" fontWeight={700}>Заявка в кабинете</Typography>
        <Box sx={{ px: 1.25, py: 0.25, borderRadius: '99px', bgcolor: '#e5ecff', color: tokens.info, fontSize: 12, fontWeight: 600 }}>Оценка</Box>
      </Stack>
      <Stack direction="row" alignItems="center" sx={{ mt: 2 }}>
        {previewStages.map((stage, index) => (
          <Stack key={stage.label} direction="row" alignItems="center" sx={{ flex: index < previewStages.length - 1 ? 1 : 'none' }}>
            <Box sx={{ width: 14, height: 14, borderRadius: '99px', flexShrink: 0,
              bgcolor: stage.state === 'done' ? tokens.ink : stage.state === 'now' ? tokens.red : '#fff',
              border: stage.state === 'next' ? `2px solid ${tokens.line}` : 'none', boxShadow: stage.state === 'now' ? `0 0 0 4px rgba(239,49,36,0.18)` : 'none' }} />
            {index < previewStages.length - 1 && <Box sx={{ flex: 1, height: 2, mx: 0.5, bgcolor: stage.state === 'done' ? tokens.ink : tokens.line }} />}
          </Stack>
        ))}
      </Stack>
      <Stack direction="row" justifyContent="space-between" sx={{ mt: 0.75 }}>
        {previewStages.map((stage) => <Typography key={stage.label} sx={{ fontSize: 11, color: tokens.neutral }}>{stage.label}</Typography>)}
      </Stack>
      <Typography variant="body2" sx={{ mt: 2, color: tokens.text }}>Банк получил данные из реестра, налоговой и бюро и оценивает заявку.</Typography>
      <Box sx={{ mt: 2, p: 1.5, borderRadius: '16px 16px 16px 4px', bgcolor: tokens.ink, color: '#fff' }}>
        <Typography sx={{ fontSize: 12, opacity: 0.7 }}>Помощник</Typography>
        <Typography variant="body2">Объясню простыми словами, что значит каждый этап и решение банка.</Typography>
      </Box>
    </Box>
  );
}

const pill = { borderRadius: '99px', paddingInline: 3 } as const;
const heroButton = { flex: { xs: '1 1 100%', sm: '0 0 auto' }, whiteSpace: { xs: 'normal', sm: 'nowrap' }, minHeight: 60, paddingInline: 4, fontSize: 17, fontWeight: 600, borderRadius: '16px' } as const;

const audiences = [
  { title: 'Предпринимателю', bg: tokens.red, color: '#fff', art: 'briefcase', action: 'Войти как предприниматель',
    points: ['Подбор продукта и платёж ещё до заявки', 'Статус и «что нужно от вас» на каждом шаге', 'Предложение, подпись кодом и график платежей в кабинете'] },
  { title: 'Банку', bg: tokens.dark, color: '#fff', art: 'chart', action: 'Рабочее место банка',
    points: ['Очередь заявок со сроками рассмотрения', 'Оценка по восьми факторам, расхождения и данные источников', 'Правила с версиями, воронка и время на этапах'] }
];

export function LandingPage() {
  usePageTitle('Кредит для бизнеса онлайн');
  const { session } = useAuth();
  const products = useApi(productsApi.list);
  const purposes = useApi(productsApi.purposes);
  const cabinet = session ? roleHome[session.user.role] : null;
  const startPath = session ? (session.user.role === 'CLIENT' ? '/client/new' : cabinet!) : '/sign-up';

  const availableProducts = products.data?.filter((item) => item.active !== false);
  const byCode = (code: string) => availableProducts?.find((item) => item.code === code);
  const gov = byCode('GOV_PROGRAM');
  const overdraft = byCode('OVERDRAFT');
  const largest = availableProducts?.reduce((max, item) => Math.max(max, item.maxAmount), 0);

  return (
    <Box sx={{ background: nightGlow, minHeight: '100vh' }}>
      {/* Header and the first screen share the dark band. */}
      <Box component="header" sx={{ position: 'sticky', top: 0, zIndex: 10, pt: { xs: 1, md: 2 } }}>
        <Container maxWidth="lg">
        <Box sx={{ display: 'flex', alignItems: 'center', gap: { xs: 1.5, md: 4 }, minHeight: { xs: 56, md: 64 }, px: { xs: 1.5, md: 2.5 }, borderRadius: '99px',
          bgcolor: 'rgba(28, 28, 30, 0.72)', backdropFilter: 'blur(16px)', border: '1px solid rgba(255, 255, 255, 0.08)' }}>
          <BrandMark inverted compact />
          <Stack component="nav" aria-label="Разделы страницы" direction="row" spacing={3} sx={{ display: { xs: 'none', md: 'flex' }, flexGrow: 1, ml: 2 }}>
            {[['Кредиты', '#products'], ['Расчёт', '#calculator'], ['Задачи', '#purposes'], ['Как получить', '#steps']].map(([label, href]) => (
              <Typography key={href} component="a" href={href} sx={{ color: '#fff', textDecoration: 'none', fontWeight: 500, opacity: 0.88, '&:hover': { opacity: 1 } }}>{label}</Typography>
            ))}
          </Stack>
          <Box sx={{ flexGrow: { xs: 1, md: 0 } }} />
          {cabinet ? (
            <Button component={RouterLink} to={cabinet} variant="contained" sx={pill}>В кабинет</Button>
          ) : (
            <Stack direction="row" spacing={1}>
              <Button component={RouterLink} to="/sign-up"
                sx={{ ...pill, display: { xs: 'none', sm: 'inline-flex' }, bgcolor: tokens.nightButton, color: '#fff', '&:hover': { bgcolor: '#3a3a3f' } }}>Регистрация</Button>
              <Button component={RouterLink} to="/sign-in" variant="contained" sx={pill}>Войти</Button>
            </Stack>
          )}
        </Box>
        </Container>
      </Box>

      <Box component="main">
        <Container maxWidth="lg" sx={{ pt: { xs: 2, md: 4 }, pb: { xs: 8, md: 12 } }}>
          <Grid container spacing={{ xs: 2, md: 3 }}>
            <Grid item xs={12} md={8}>
              <Stack spacing={{ xs: 2, md: 3 }} sx={{ height: '100%' }}>
                <Box sx={{ position: 'relative', overflow: 'hidden', borderRadius: '32px', bgcolor: tokens.tileSalmon, p: { xs: 3, md: 4.5 }, minHeight: { xs: 380, md: 420 }, flexGrow: 1,
                  display: 'flex', alignItems: 'center', gap: 4 }}>
                  <Box sx={{ position: 'relative', zIndex: 1, flex: 1, minWidth: 0, maxWidth: { xs: '100%', sm: '68%', md: 'none' } }}>
                    <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap sx={{ mb: 2.5 }}>
                      {['Для ИП и малого бизнеса', 'Онлайн, без визита в офис'].map((label) => (
                        <Box key={label} sx={{ px: 1.5, py: 0.5, borderRadius: '99px', bgcolor: 'rgba(255,255,255,0.7)', fontSize: 14, fontWeight: 600, color: tokens.ink }}>{label}</Box>
                      ))}
                    </Stack>
                    <Typography component="h1" sx={{ fontFamily: fontDisplay, fontWeight: 800, fontSize: { xs: 36, md: 46 }, lineHeight: 1.04, letterSpacing: '-0.03em', color: tokens.ink }}>
                      Кредит для бизнеса онлайн
                    </Typography>
                    <Typography sx={{ mt: 2, fontSize: { xs: 16, md: 18 }, color: tokens.text }}>
                      Подбор продукта, заявка, решение банка и подписание договора — в одном кабинете, без визита в офис.
                    </Typography>
                    {/* Wraps instead of squeezing: each button keeps its padding at every width. */}
                    <Stack direction="row" flexWrap="wrap" useFlexGap spacing={1.5} sx={{ mt: 4 }}>
                      <Button component={RouterLink} to={startPath} variant="contained" size="large" sx={heroButton}>Оформить заявку онлайн</Button>
                      <Button href="#calculator" size="large" sx={{ ...heroButton, bgcolor: 'rgba(255,255,255,0.6)', '&:hover': { bgcolor: 'rgba(255,255,255,0.85)' } }}>Рассчитать платёж</Button>
                    </Stack>
                  </Box>
                  <Box aria-hidden sx={{ position: 'relative', flexShrink: 0, display: { xs: 'none', md: 'block' }, zIndex: 1 }}>
                    <Illustration name="coin" size={150} sx={{ position: 'absolute', right: -40, top: -36, transform: 'rotate(-12deg)', zIndex: -1 }} />
                    <CabinetPreview />
                  </Box>
                  <Box aria-hidden sx={{ position: 'absolute', right: -20, bottom: -24, width: 220, height: 200, display: { xs: 'none', sm: 'block', md: 'none' } }}>
                    <Illustration name="coin" size={{ xs: 170, md: 230 }} sx={{ position: 'absolute', right: 0, top: 0, transform: 'rotate(-8deg)' }} />
                    <Illustration name="credit-card" size={{ xs: 130, md: 170 }} sx={{ position: 'absolute', left: 0, bottom: 0, transform: 'rotate(10deg)' }} />
                    <Illustration name="chart" size={{ xs: 90, md: 110 }} sx={{ position: 'absolute', right: { xs: 10, md: 0 }, bottom: { xs: -10, md: -16 } }} />
                  </Box>
                </Box>
                {/* Stack resets child margins; the grid needs its own negative ones, so it gets a wrapper. */}
                <Box>
                <Grid container spacing={{ xs: 2, md: 3 }}>
                  <Grid item xs={12} sm={6}>
                    <PromoTile bg={tokens.tileGreen} title="Решение за минуты" text="По небольшим суммам — без участия сотрудника" art="check" to={startPath} minHeight={{ xs: 150, md: 170 }} artSize={96} />
                  </Grid>
                  <Grid item xs={12} sm={6}>
                    <PromoTile bg={tokens.tilePeach} title="Без визита в офис" text="Договор подписывается кодом прямо в кабинете" art="pen" to={startPath} minHeight={{ xs: 150, md: 170 }} artSize={96} />
                  </Grid>
                </Grid>
                </Box>
              </Stack>
            </Grid>
            <Grid item xs={12} md={4}>
              <Stack spacing={{ xs: 2, md: 3 }} sx={{ height: '100%' }}>
                {gov && <Box sx={{ flex: 1 }}>
                  <PromoTile bg={tokens.tilePurple} color="#fff" align="center" art="bank" to={startPath} minHeight={{ xs: 260, md: 300 }} artSize={150}
                    title={gov ? `Кредит с господдержкой от ${percent(gov.baseRate)}` : 'Кредит с господдержкой'}
                    text={gov ? `до ${rub(gov.maxAmount)} для приоритетных отраслей` : undefined} />
                </Box>}
                {overdraft && <Box sx={{ flex: 1 }}>
                  <PromoTile bg={tokens.tileCyan} align="center" art="hourglass" to={startPath} minHeight={{ xs: 260, md: 300 }} artSize={150}
                    title="Овердрафт для кассовых разрывов"
                    text={overdraft ? `до ${rub(overdraft.maxAmount)}, проценты только за дни использования` : 'Проценты только за дни использования'} />
                </Box>}
              </Stack>
            </Grid>
          </Grid>
        </Container>

        {/* The white sheet slides over the dark band with large rounded corners. */}
        <Box sx={{ bgcolor: '#fff', borderRadius: { xs: '32px 32px 0 0', md: '80px 80px 0 0' }, mt: { xs: -4, md: -6 }, position: 'relative' }}>
          <Container maxWidth="lg" component="section" id="products" aria-labelledby="products-title" sx={{ pt: { xs: 6, md: 10 }, pb: { xs: 6, md: 8 } }}>
            <Typography id="products-title" component="h2" sx={sectionTitle}>Лучшее для бизнеса</Typography>
            <Typography color="text.secondary" sx={{ mt: 1.5, mb: 4, fontSize: 18 }}>
              Кредиты для малого и микробизнеса{largest ? ` — до ${rub(largest)}` : ''}
            </Typography>
            <Grid container spacing={{ xs: 2, md: 3 }}>
              {products.error ? <Grid item xs={12}><ErrorState message={products.error} onRetry={products.reload} /></Grid> : availableProducts?.length === 0 ?
                <Grid item xs={12}><Typography role="status">В каталоге пока нет доступных продуктов.</Typography></Grid> :
              (availableProducts ?? Array.from({ length: 5 }, () => null)).map((product, index) => (
                <Grid item xs={12} sm={6} md={4} key={product?.code ?? index}>
                  <ProductTile product={product} to={product ? financingPath(startPath, { product: product.code, purpose: product.purposes[0], amount: Math.max(product.minAmount, Math.min(1_000_000, product.maxAmount)), termMonths: Math.max(product.minTermMonths, Math.min(12, product.maxTermMonths)) }) : startPath} />
                </Grid>
              ))}
              <Grid item xs={12} sm={6} md={4}>
                <PromoTile bg={tokens.tilePink} align="center" art="briefcase" to={startPath} minHeight={{ xs: 240, md: 300 }} artSize={150}
                  title="Подобрать кредит под задачу" text="Ответьте на два вопроса — покажем подходящие продукты" />
              </Grid>
            </Grid>
          </Container>

          <Container maxWidth="lg" component="section" aria-labelledby="audiences-title" sx={{ pb: { xs: 6, md: 10 } }}>
            <Typography id="audiences-title" component="h2" sx={{ ...sectionTitle, mb: 4 }}>Один процесс для клиента и банка</Typography>
            <Grid container spacing={{ xs: 2, md: 3 }}>
              {audiences.map((audience) => (
                <Grid item xs={12} md={6} key={audience.title}>
                  <Box sx={{ position: 'relative', overflow: 'hidden', height: '100%', p: { xs: 3, md: 4.5 }, borderRadius: '32px', bgcolor: audience.bg, color: audience.color }}>
                    <Illustration name={audience.art} size={{ xs: 72, md: 130 }} sx={{ position: { xs: 'static', md: 'absolute' }, display: 'block', mb: { xs: 1.5, md: 0 }, right: 24, top: 24 }} />
                    <Typography component="h3" sx={{ fontFamily: fontDisplay, fontWeight: 800, fontSize: { xs: 26, md: 32 }, maxWidth: '65%' }}>{audience.title}</Typography>
                    <Stack component="ul" spacing={1.5} sx={{ mt: 3, mb: 4, p: 0, listStyle: 'none' }}>
                      {audience.points.map((point) => (
                        <Stack component="li" key={point} direction="row" spacing={1.5} alignItems="flex-start">
                          <Box aria-hidden sx={{ mt: '9px', width: 8, height: 8, borderRadius: '99px', bgcolor: '#fff', opacity: 0.8, flexShrink: 0 }} />
                          <Typography sx={{ opacity: 0.92 }}>{point}</Typography>
                        </Stack>
                      ))}
                    </Stack>
                    <Button component={RouterLink} to={cabinet ?? '/sign-in'} sx={{ bgcolor: '#fff', color: tokens.ink, '&:hover': { bgcolor: '#f1f1f1' } }}>{audience.action}</Button>
                  </Box>
                </Grid>
              ))}
            </Grid>
          </Container>

          <Box component="section" id="calculator" aria-labelledby="calculator-title" sx={{ bgcolor: tokens.tile, py: { xs: 6, md: 10 } }}>
            <Container maxWidth="lg">
              <Grid container spacing={{ xs: 4, md: 8 }} alignItems="center">
                <Grid item xs={12} md={6}>
                  <Typography id="calculator-title" component="h2" sx={sectionTitle}>Посчитайте платёж до заявки</Typography>
                  <Typography color="text.secondary" sx={{ mt: 2, fontSize: 18 }}>
                    Калькулятор подбирает продукт по цели и считает ежемесячный платёж по тем же правилам, что и кабинет после входа.
                  </Typography>
                  <Stack spacing={2} sx={{ mt: 4 }}>
                    {[['shield', 'Расчёт не запрашивает кредитную историю и не создаёт заявку'], ['gem', 'Итоговая ставка — в предложении банка после оценки заявки']].map(([art, text]) => (
                      <Stack key={art} direction="row" spacing={2} alignItems="center">
                        <Illustration name={art} size={48} />
                        <Typography>{text}</Typography>
                      </Stack>
                    ))}
                  </Stack>
                </Grid>
                <Grid item xs={12} md={6}>
                  <LandingCalculator startPath={startPath} />
                </Grid>
              </Grid>
            </Container>
          </Box>

          <Container maxWidth="lg" component="section" id="purposes" aria-labelledby="purposes-title" sx={{ py: { xs: 6, md: 10 } }}>
            <Stack direction={{ xs: 'column', md: 'row' }} justifyContent="space-between" alignItems={{ md: 'flex-end' }} spacing={2} sx={{ mb: 4 }}>
              <Box>
                <Typography id="purposes-title" component="h2" sx={sectionTitle}>Деньги под вашу задачу</Typography>
                <Typography color="text.secondary" sx={{ mt: 1.5, fontSize: 18 }}>Выберите, на что нужны деньги, — подберём продукт и посчитаем платёж</Typography>
              </Box>
              <Button component={RouterLink} to={startPath} variant="outlined" sx={{ alignSelf: { xs: 'flex-start', md: 'auto' } }}>Все продукты</Button>
            </Stack>
            <Grid container spacing={{ xs: 2, md: 3 }}>
              {(purposes.data ?? []).map((item) => {
                const text = purposeText[item.code];
                return (
                  <Grid item xs={12} md={6} key={item.code}>
                    <ButtonBase component={RouterLink} to={financingPath(startPath, { purpose: item.code })} focusRipple
                      sx={{ display: 'flex', alignItems: 'center', gap: 2.5, width: '100%', height: '100%', p: { xs: 2.5, md: 3 }, borderRadius: '28px', bgcolor: tokens.tileLime, color: tokens.ink, textAlign: 'left',
                        transition: 'filter 0.2s ease', '&:hover': { filter: 'brightness(0.97)' } }}>
                      <Illustration name={purposeArt[item.code] ?? 'coin'} size={{ xs: 56, md: 64 }} />
                      <Box sx={{ flexGrow: 1 }}>
                        <Typography component="h3" fontWeight={700} sx={{ fontSize: { xs: 18, md: 20 } }}>{item.title}</Typography>
                        {text && <Typography sx={{ mt: 0.5, color: tokens.text }}>{text}</Typography>}
                      </Box>
                      <ChevronRightRoundedIcon aria-hidden sx={{ color: tokens.neutral }} />
                    </ButtonBase>
                  </Grid>
                );
              })}
            </Grid>
          </Container>

          <Container maxWidth="lg" component="section" id="steps" aria-labelledby="steps-title" sx={{ pb: { xs: 6, md: 10 } }}>
            <Typography id="steps-title" component="h2" sx={{ ...sectionTitle, mb: 4 }}>Как получить кредит</Typography>
            <Box sx={{ p: { xs: 3, md: 5 }, borderRadius: '32px', bgcolor: tokens.tile }}>
              <Grid container spacing={{ xs: 3, md: 4 }} component="ol" sx={{ listStyle: 'none', p: 0, m: 0 }}>
                {steps.map((step, index) => (
                  <Grid item xs={12} sm={6} md={3} key={step.title} component="li">
                    <Stack direction="row" justifyContent="space-between" alignItems="flex-start">
                      <Typography aria-hidden sx={{ fontFamily: fontDisplay, fontWeight: 800, fontSize: { xs: 44, md: 56 }, lineHeight: 1, color: tokens.red }}>{index + 1}</Typography>
                      <Illustration name={step.art} size={64} />
                    </Stack>
                    <Typography component="h3" fontWeight={700} sx={{ mt: 2, fontSize: 20 }}>{step.title}</Typography>
                    <Typography color="text.secondary" sx={{ mt: 1 }}>{step.text}</Typography>
                  </Grid>
                ))}
              </Grid>
            </Box>
          </Container>

          <Container maxWidth="lg" sx={{ pb: { xs: 6, md: 10 } }}>
            <Box sx={{ position: 'relative', overflow: 'hidden', borderRadius: '32px', background: `linear-gradient(120deg, ${tokens.red} 0%, #c4211a 55%, #4a0d10 100%)`, color: '#fff', p: { xs: 3, md: 5 }, display: 'flex', flexDirection: { xs: 'column', md: 'row' }, alignItems: { md: 'center' }, gap: 3 }}>
              <Box sx={{ flexGrow: 1, position: 'relative', zIndex: 1 }}>
                <Typography component="h2" sx={{ fontFamily: fontDisplay, fontWeight: 800, fontSize: { xs: 26, md: 36 }, lineHeight: 1.15 }}>Подключите компанию за пару минут</Typography>
                <Typography sx={{ mt: 1, opacity: 0.9 }}>Реквизиты подтянутся из реестра по ИНН — останется выбрать продукт</Typography>
              </Box>
              <Button component={RouterLink} to={startPath} size="large" sx={{ whiteSpace: 'nowrap', flexShrink: 0, bgcolor: '#fff', color: tokens.ink, position: 'relative', zIndex: 1, alignSelf: { xs: 'flex-start', md: 'center' }, '&:hover': { bgcolor: '#f1f1f1' } }}>
                {session ? 'Оформить заявку' : 'Подключить компанию'}
              </Button>
              <Illustration name="handshake" size={{ xs: 110, md: 150 }} sx={{ position: { xs: 'absolute', md: 'static' }, right: 16, bottom: -16, opacity: { xs: 0.35, md: 1 } }} />
            </Box>
          </Container>

          <Container maxWidth="md" component="section" aria-labelledby="faq-title" sx={{ pb: { xs: 8, md: 12 } }}>
            <Typography id="faq-title" component="h2" sx={{ ...sectionTitle, mb: 3 }}>Частые вопросы</Typography>
            {faq.map((item) => (
              <Accordion key={item.q} disableGutters elevation={0} sx={{ bgcolor: tokens.tile, borderRadius: '20px !important', mb: 1.5, '&::before': { display: 'none' } }}>
                <AccordionSummary expandIcon={<ExpandMoreIcon />} sx={{ px: 3, minHeight: 64 }}>
                  <Typography fontWeight={600}>{item.q}</Typography>
                </AccordionSummary>
                <AccordionDetails sx={{ px: 3, pb: 3 }}>
                  <Typography color="text.secondary">{item.a}</Typography>
                </AccordionDetails>
              </Accordion>
            ))}
          </Container>
        </Box>
      </Box>

      <Box component="footer" sx={{ bgcolor: tokens.night, color: 'rgba(255,255,255,0.72)', py: 6 }}>
        <Container maxWidth="lg">
          <BrandMark inverted />
          <Typography variant="body2" sx={{ mt: 2, maxWidth: 720 }}>
            Прототип для хакатона. Все компании, заявки и решения — синтетические; реальные кредиты не выдаются,
            внешние реестры и бюро заменены симуляторами. Иллюстрации — Fluent Emoji 3D (MIT).
          </Typography>
        </Container>
      </Box>
    </Box>
  );
}
