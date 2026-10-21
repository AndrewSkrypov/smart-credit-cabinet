import ExpandMoreIcon from '@mui/icons-material/ExpandMore';
import { Accordion, AccordionDetails, AccordionSummary, Box, Button, Container, Grid, Paper, Skeleton, Stack, Typography } from '@mui/material';
import { Link as RouterLink } from 'react-router-dom';
import { productsApi } from '../../api/client';
import { roleHome, useAuth } from '../../auth/AuthProvider';
import { BrandMark } from '../../components/BrandMark';
import { useApi } from '../../hooks/useApi';
import { usePageTitle } from '../../hooks/usePageTitle';
import { months, percent, rub } from '../../shared/format';
import { tokens } from '../../theme/theme';
import { LandingCalculator } from './LandingCalculator';

const steps = [
  { title: 'Подберите продукт', text: 'Ответьте, на что нужны деньги, — кабинет предложит подходящий кредит и посчитает платёж.' },
  { title: 'Заполните заявку', text: 'Реквизиты компании подтянутся из реестра по ИНН. Черновик сохраняется сам.' },
  { title: 'Дождитесь решения', text: 'Банк сам запросит выписки из реестров, налоговой и бюро. Статус виден на каждом шаге.' },
  { title: 'Подпишите онлайн', text: 'Примите предложение и подпишите договор простой электронной подписью — без визита в офис.' }
];

const advantages = [
  { value: 'от 5 минут', text: 'решение по небольшим суммам без участия сотрудника' },
  { value: '0 визитов', text: 'заявка, документы, предложение и подписание — в одном кабинете' },
  { value: '24/7', text: 'статус заявки и история событий доступны в любое время' }
];

const faq = [
  { q: 'Кто может подать заявку?', a: 'Индивидуальные предприниматели и компании малого бизнеса, зарегистрированные в России. Нужен ИНН и данные о выручке за год.' },
  { q: 'Какие документы понадобятся?', a: 'Чаще всего никаких: банк сам получает сведения из реестра, налоговой и бюро кредитных историй. Если данных не хватит, банк попросит загрузить конкретный документ прямо в кабинете.' },
  { q: 'Что будет, если банк не одобрит всю сумму?', a: 'Банк может сделать контрпредложение — меньшую сумму или другой срок. Его можно принять или отклонить.' },
  { q: 'Как подписать договор?', a: 'Простой электронной подписью: кабинет покажет код подтверждения, его нужно ввести в окне подписания.' }
];

function ProductsSection() {
  const products = useApi(productsApi.list);
  return (
    <Box component="section" aria-labelledby="products-title" sx={{ py: { xs: 6, md: 10 } }}>
      <Container maxWidth="lg">
        <Typography id="products-title" variant="h4" component="h2" fontWeight={700} sx={{ mb: 4, fontSize: { xs: 26, md: 34 } }}>Кредиты для бизнеса</Typography>
        <Grid container spacing={2}>
          {(products.data ?? Array.from({ length: 3 }, () => null)).map((product, index) => (
            <Grid item xs={12} sm={6} md={4} key={product?.code ?? index}>
              <Paper sx={{ p: 3, height: '100%', borderRadius: '24px', display: 'flex', flexDirection: 'column' }}>
                {product ? (
                  <>
                    <Typography variant="h6" component="h3" fontWeight={700}>{product.name}</Typography>
                    <Typography color="text.secondary" sx={{ mt: 1, flexGrow: 1 }}>{product.description}</Typography>
                    <Stack direction="row" spacing={3} sx={{ mt: 3 }}>
                      <Box>
                        <Typography variant="h6" component="p">до {rub(product.maxAmount)}</Typography>
                        <Typography variant="body2" color="text.secondary">сумма</Typography>
                      </Box>
                      <Box>
                        <Typography variant="h6" component="p">от {percent(product.baseRate)}</Typography>
                        <Typography variant="body2" color="text.secondary">ставка</Typography>
                      </Box>
                    </Stack>
                    <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
                      Срок {product.minTermMonths === product.maxTermMonths ? months(product.maxTermMonths) : `до ${months(product.maxTermMonths)}`}
                    </Typography>
                  </>
                ) : (
                  <Skeleton variant="rounded" height={180} />
                )}
              </Paper>
            </Grid>
          ))}
        </Grid>
      </Container>
    </Box>
  );
}

export function LandingPage() {
  usePageTitle('Кредит для бизнеса онлайн');
  const { session } = useAuth();
  const cabinet = session ? roleHome[session.user.role] : null;
  const startPath = session ? (session.user.role === 'CLIENT' ? '/client/new' : cabinet!) : '/sign-up';

  return (
    <Box sx={{ bgcolor: 'background.default', minHeight: '100vh' }}>
      <Box component="header" sx={{ bgcolor: '#fff', borderBottom: `1px solid ${tokens.line}` }}>
        <Container maxWidth="lg" sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', minHeight: 64, gap: 2 }}>
          <BrandMark compact />
          {cabinet ? (
            <Button component={RouterLink} to={cabinet} variant="contained">В кабинет</Button>
          ) : (
            <Stack direction="row" spacing={1}>
              <Button component={RouterLink} to="/sign-in" color="secondary" variant="text">Войти</Button>
              <Button component={RouterLink} to="/sign-up" variant="contained" sx={{ display: { xs: 'none', sm: 'inline-flex' } }}>Регистрация</Button>
            </Stack>
          )}
        </Container>
      </Box>

      <Box component="main">
        <Box component="section" sx={{ bgcolor: '#fff', py: { xs: 5, md: 10 } }}>
          <Container maxWidth="lg">
            <Grid container spacing={{ xs: 4, md: 8 }} alignItems="center">
              <Grid item xs={12} md={6}>
                <Typography variant="h3" component="h1" fontWeight={700} sx={{ fontSize: { xs: 32, md: 48 }, lineHeight: 1.1 }}>
                  Кредит для бизнеса онлайн
                </Typography>
                <Typography variant="h6" component="p" color="text.secondary" fontWeight={400} sx={{ mt: 2, maxWidth: 520 }}>
                  Подбор продукта, заявка, решение банка и подписание договора — в одном кабинете, без визита в офис.
                </Typography>
                <Box sx={{ mt: 4, display: 'grid', gridTemplateColumns: { xs: '1fr', sm: 'repeat(3, 1fr)' }, gap: { xs: 2, sm: 3 } }}>
                  {advantages.map((item) => (
                    <Box key={item.value}>
                      <Typography variant="h5" component="p" fontWeight={700} sx={{ whiteSpace: 'nowrap' }}>{item.value}</Typography>
                      <Typography variant="body2" color="text.secondary">{item.text}</Typography>
                    </Box>
                  ))}
                </Box>
              </Grid>
              <Grid item xs={12} md={6}>
                <LandingCalculator startPath={startPath} />
              </Grid>
            </Grid>
          </Container>
        </Box>

        <ProductsSection />

        <Box component="section" aria-labelledby="steps-title" sx={{ bgcolor: '#fff', py: { xs: 6, md: 10 } }}>
          <Container maxWidth="lg">
            <Typography id="steps-title" variant="h4" component="h2" fontWeight={700} sx={{ mb: 4, fontSize: { xs: 26, md: 34 } }}>Как получить кредит</Typography>
            <Grid container spacing={3} component="ol" sx={{ listStyle: 'none', p: 0, m: 0 }}>
              {steps.map((step, index) => (
                <Grid item xs={12} sm={6} md={3} key={step.title} component="li">
                  <Box sx={{ width: 48, height: 48, borderRadius: '99px', bgcolor: tokens.secondaryFill, display: 'grid', placeItems: 'center', fontWeight: 700, fontSize: 20 }}>
                    {index + 1}
                  </Box>
                  <Typography variant="h6" component="h3" fontWeight={700} sx={{ mt: 2 }}>{step.title}</Typography>
                  <Typography color="text.secondary" sx={{ mt: 1 }}>{step.text}</Typography>
                </Grid>
              ))}
            </Grid>
            <Button component={RouterLink} to={startPath} variant="contained" size="large" sx={{ mt: 5 }}>Оформить заявку онлайн</Button>
          </Container>
        </Box>

        <Box component="section" aria-labelledby="faq-title" sx={{ py: { xs: 6, md: 10 } }}>
          <Container maxWidth="md">
            <Typography id="faq-title" variant="h4" component="h2" fontWeight={700} sx={{ mb: 3, fontSize: { xs: 26, md: 34 } }}>Частые вопросы</Typography>
            {faq.map((item) => (
              <Accordion key={item.q} disableGutters elevation={0} sx={{ borderRadius: '16px !important', mb: 1, '&::before': { display: 'none' } }}>
                <AccordionSummary expandIcon={<ExpandMoreIcon />}>
                  <Typography fontWeight={600}>{item.q}</Typography>
                </AccordionSummary>
                <AccordionDetails>
                  <Typography color="text.secondary">{item.a}</Typography>
                </AccordionDetails>
              </Accordion>
            ))}
          </Container>
        </Box>
      </Box>

      <Box component="footer" sx={{ bgcolor: tokens.dark, color: 'rgba(255,255,255,0.72)', py: 5 }}>
        <Container maxWidth="lg">
          <BrandMark inverted />
          <Typography variant="body2" sx={{ mt: 2, maxWidth: 720 }}>
            Прототип для хакатона. Все компании, заявки и решения — синтетические; реальные кредиты не выдаются,
            внешние реестры и бюро заменены симуляторами.
          </Typography>
        </Container>
      </Box>
    </Box>
  );
}
