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
import { productArt, purposeArt } from '../../shared/illustrations';
import { tokens } from '../../theme/theme';
import { FinancingPurpose, Product } from '../../types';
import { LandingCalculator } from './LandingCalculator';

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

const sectionTitle = { fontSize: { xs: 28, md: 40 }, fontWeight: 700, letterSpacing: '-0.01em', lineHeight: 1.15 } as const;

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

export function LandingPage() {
  usePageTitle('Кредит для бизнеса онлайн');
  const { session } = useAuth();
  const products = useApi(productsApi.list);
  const purposes = useApi(productsApi.purposes);
  const cabinet = session ? roleHome[session.user.role] : null;
  const startPath = session ? (session.user.role === 'CLIENT' ? '/client/new' : cabinet!) : '/sign-up';

  const byCode = (code: string) => products.data?.find((item) => item.code === code);
  const gov = byCode('GOV_PROGRAM');
  const overdraft = byCode('OVERDRAFT');
  const largest = products.data?.reduce((max, item) => Math.max(max, item.maxAmount), 0);

  return (
    <Box sx={{ bgcolor: tokens.night, minHeight: '100vh' }}>
      {/* Header and the first screen share the dark band. */}
      <Box component="header">
        <Container maxWidth="lg" sx={{ display: 'flex', alignItems: 'center', gap: { xs: 1.5, md: 4 }, minHeight: { xs: 64, md: 88 } }}>
          <BrandMark inverted compact />
          <Stack component="nav" aria-label="Разделы страницы" direction="row" spacing={3} sx={{ display: { xs: 'none', md: 'flex' }, flexGrow: 1, ml: 2 }}>
            {[['Кредиты', '#products'], ['Расчёт', '#calculator'], ['Задачи', '#purposes'], ['Как получить', '#steps']].map(([label, href]) => (
              <Typography key={href} component="a" href={href} sx={{ color: '#fff', textDecoration: 'none', fontWeight: 500, opacity: 0.88, '&:hover': { opacity: 1 } }}>{label}</Typography>
            ))}
          </Stack>
          <Box sx={{ flexGrow: { xs: 1, md: 0 } }} />
          {cabinet ? (
            <Button component={RouterLink} to={cabinet} variant="contained">В кабинет</Button>
          ) : (
            <Stack direction="row" spacing={1}>
              <Button component={RouterLink} to="/sign-up"
                sx={{ display: { xs: 'none', sm: 'inline-flex' }, bgcolor: tokens.nightButton, color: '#fff', '&:hover': { bgcolor: '#3a3a3f' } }}>Регистрация</Button>
              <Button component={RouterLink} to="/sign-in" variant="contained">Войти</Button>
            </Stack>
          )}
        </Container>
      </Box>

      <Box component="main">
        <Container maxWidth="lg" sx={{ pt: { xs: 2, md: 4 }, pb: { xs: 8, md: 12 } }}>
          <Grid container spacing={{ xs: 2, md: 3 }}>
            <Grid item xs={12} md={8}>
              <Stack spacing={{ xs: 2, md: 3 }} sx={{ height: '100%' }}>
                <Box sx={{ position: 'relative', overflow: 'hidden', borderRadius: '32px', bgcolor: tokens.tileSalmon, p: { xs: 3, md: 5 }, minHeight: { xs: 380, md: 420 }, flexGrow: 1 }}>
                  <Box sx={{ position: 'relative', zIndex: 1, maxWidth: { xs: '100%', sm: '68%' } }}>
                    <Typography component="h1" fontWeight={700} sx={{ fontSize: { xs: 36, md: 52 }, lineHeight: 1.05, letterSpacing: '-0.02em', color: tokens.ink }}>
                      Кредит для бизнеса онлайн
                    </Typography>
                    <Typography sx={{ mt: 2, fontSize: { xs: 16, md: 18 }, color: tokens.text }}>
                      Подбор продукта, заявка, решение банка и подписание договора — в одном кабинете, без визита в офис.
                    </Typography>
                    <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5} sx={{ mt: 4 }}>
                      <Button component={RouterLink} to={startPath} variant="contained" size="large" sx={{ whiteSpace: 'nowrap' }}>Оформить заявку онлайн</Button>
                      <Button href="#calculator" size="large" sx={{ whiteSpace: 'nowrap', bgcolor: 'rgba(255,255,255,0.6)', '&:hover': { bgcolor: 'rgba(255,255,255,0.85)' } }}>Рассчитать платёж</Button>
                    </Stack>
                  </Box>
                  <Box aria-hidden sx={{ position: 'absolute', right: { xs: -20, md: 24 }, bottom: { xs: -24, md: 24 }, width: { xs: 220, md: 300 }, height: { xs: 200, md: 280 }, display: { xs: 'none', sm: 'block' } }}>
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
                <Box sx={{ flex: 1 }}>
                  <PromoTile bg={tokens.tilePurple} color="#fff" align="center" art="bank" to={startPath} minHeight={{ xs: 260, md: 300 }} artSize={150}
                    title={gov ? `Кредит с господдержкой от ${percent(gov.baseRate)}` : 'Кредит с господдержкой'}
                    text={gov ? `до ${rub(gov.maxAmount)} для приоритетных отраслей` : undefined} />
                </Box>
                <Box sx={{ flex: 1 }}>
                  <PromoTile bg={tokens.tileCyan} align="center" art="hourglass" to={startPath} minHeight={{ xs: 260, md: 300 }} artSize={150}
                    title="Овердрафт для кассовых разрывов"
                    text={overdraft ? `до ${rub(overdraft.maxAmount)}, проценты только за дни использования` : 'Проценты только за дни использования'} />
                </Box>
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
              {(products.data ?? Array.from({ length: 5 }, () => null)).map((product, index) => (
                <Grid item xs={12} sm={6} md={4} key={product?.code ?? index}>
                  <ProductTile product={product} to={startPath} />
                </Grid>
              ))}
              <Grid item xs={12} sm={6} md={4}>
                <PromoTile bg={tokens.tilePink} align="center" art="briefcase" to={startPath} minHeight={{ xs: 240, md: 300 }} artSize={150}
                  title="Подобрать кредит под задачу" text="Ответьте на два вопроса — покажем подходящие продукты" />
              </Grid>
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
                    <ButtonBase component={RouterLink} to={startPath} focusRipple
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
            <Grid container spacing={{ xs: 2, md: 3 }} component="ol" sx={{ listStyle: 'none', p: 0, m: 0 }}>
              {steps.map((step, index) => (
                <Grid item xs={12} sm={6} md={3} key={step.title} component="li">
                  <Box sx={{ height: '100%', p: 3, borderRadius: '28px', bgcolor: tokens.tile }}>
                    <Stack direction="row" justifyContent="space-between" alignItems="flex-start">
                      <Box sx={{ width: 40, height: 40, borderRadius: '99px', bgcolor: tokens.ink, color: '#fff', display: 'grid', placeItems: 'center', fontWeight: 700 }}>{index + 1}</Box>
                      <Illustration name={step.art} size={72} />
                    </Stack>
                    <Typography component="h3" fontWeight={700} sx={{ mt: 2, fontSize: 20 }}>{step.title}</Typography>
                    <Typography color="text.secondary" sx={{ mt: 1 }}>{step.text}</Typography>
                  </Box>
                </Grid>
              ))}
            </Grid>
          </Container>

          <Container maxWidth="lg" sx={{ pb: { xs: 6, md: 10 } }}>
            <Box sx={{ position: 'relative', overflow: 'hidden', borderRadius: '32px', bgcolor: tokens.red, color: '#fff', p: { xs: 3, md: 5 }, display: 'flex', flexDirection: { xs: 'column', md: 'row' }, alignItems: { md: 'center' }, gap: 3 }}>
              <Box sx={{ flexGrow: 1, position: 'relative', zIndex: 1 }}>
                <Typography component="h2" fontWeight={700} sx={{ fontSize: { xs: 24, md: 32 }, lineHeight: 1.2 }}>Подключите компанию за пару минут</Typography>
                <Typography sx={{ mt: 1, opacity: 0.9 }}>Реквизиты подтянутся из реестра по ИНН — останется выбрать продукт</Typography>
              </Box>
              <Button component={RouterLink} to={startPath} size="large" sx={{ bgcolor: '#fff', color: tokens.ink, position: 'relative', zIndex: 1, alignSelf: { xs: 'flex-start', md: 'center' }, '&:hover': { bgcolor: '#f1f1f1' } }}>
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
