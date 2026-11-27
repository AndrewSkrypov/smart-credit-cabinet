import AndroidRoundedIcon from '@mui/icons-material/AndroidRounded';
import AppleIcon from '@mui/icons-material/Apple';
import DownloadRoundedIcon from '@mui/icons-material/DownloadRounded';
import IosShareRoundedIcon from '@mui/icons-material/IosShareRounded';
import { Alert, Box, Button, Container, Grid, Paper, Stack, Typography } from '@mui/material';
import { useEffect, useState } from 'react';
import { BrandMark } from '../components/BrandMark';
import { DocumentSurface } from '../components/DocumentSurface';
import { Illustration } from '../components/Illustration';
import { usePageTitle } from '../hooks/usePageTitle';

/** Published separately from the app bundle, so store links and builds change without a release. */
export interface AppDownloads {
  android: { rustoreUrl: string | null; apkUrl: string | null; version: string; sizeMb?: number };
  ios: { otaManifestUrl: string | null; version: string };
}

export type Platform = 'ios' | 'android' | 'desktop';

export function detectPlatform(userAgent: string, maxTouchPoints = 0): Platform {
  if (/android/i.test(userAgent)) return 'android';
  // iPadOS reports a desktop Safari user agent; touch points tell it apart from a Mac.
  if (/iphone|ipad|ipod/i.test(userAgent) || (/macintosh/i.test(userAgent) && maxTouchPoints > 1)) return 'ios';
  return 'desktop';
}

export function otaLink(manifestUrl: string) {
  return `itms-services://?action=download-manifest&url=${encodeURIComponent(manifestUrl)}`;
}

function AndroidCard({ downloads }: { downloads: AppDownloads | null }) {
  const android = downloads?.android;
  return (
    <Paper component="section" aria-labelledby="android-title" variant="outlined" sx={{ p: { xs: 2.5, md: 3.5 }, height: '100%', borderRadius: 4 }}>
      <Stack spacing={2}>
        <Stack direction="row" spacing={1.5} alignItems="center">
          <AndroidRoundedIcon sx={{ fontSize: 36, color: '#3DDC84' }} />
          <Typography id="android-title" variant="h5" component="h2" fontWeight={700}>Android</Typography>
        </Stack>
        <Typography color="text.secondary">Приложение «Кабинет» для телефонов на Android 7 и новее.</Typography>
        {android?.rustoreUrl && (
          <Button href={android.rustoreUrl} target="_blank" rel="noopener" variant="contained" size="large">Скачать в RuStore</Button>
        )}
        {android?.apkUrl && (
          <Button href={android.apkUrl} download variant={android.rustoreUrl ? 'outlined' : 'contained'} size="large" startIcon={<DownloadRoundedIcon />}>
            Скачать APK{android.sizeMb ? ` · ${android.sizeMb.toLocaleString('ru-RU')} МБ` : ''}
          </Button>
        )}
        {!android?.rustoreUrl && <Typography variant="body2" color="text.secondary">Публикация в RuStore на модерации — пока приложение ставится файлом.</Typography>}
        {android?.apkUrl && (
          <Typography variant="body2" color="text.secondary">
            Откройте скачанный файл и разрешите установку из этого источника. Версия {android.version}.
          </Typography>
        )}
      </Stack>
    </Paper>
  );
}

function IosCard({ downloads, platform }: { downloads: AppDownloads | null; platform: Platform }) {
  const ota = downloads?.ios.otaManifestUrl;
  return (
    <Paper component="section" aria-labelledby="ios-title" variant="outlined" sx={{ p: { xs: 2.5, md: 3.5 }, height: '100%', borderRadius: 4 }}>
      <Stack spacing={2}>
        <Stack direction="row" spacing={1.5} alignItems="center">
          <AppleIcon sx={{ fontSize: 36 }} />
          <Typography id="ios-title" variant="h5" component="h2" fontWeight={700}>iPhone и iPad</Typography>
        </Stack>
        {ota ? (
          <>
            <Typography color="text.secondary">Установка по воздуху — без App Store, прямо с этой страницы.</Typography>
            <Button href={otaLink(ota)} variant="contained" size="large" startIcon={<DownloadRoundedIcon />} disabled={platform !== 'ios'}>
              Установить на iPhone
            </Button>
            {platform !== 'ios' && <Typography variant="body2" color="text.secondary">Откройте эту страницу на iPhone, чтобы установить приложение.</Typography>}
          </>
        ) : (
          <>
            <Typography color="text.secondary">Кабинет ставится на экран «Домой» из Safari и открывается как приложение — без панелей браузера.</Typography>
            <Box component="ol" sx={{ m: 0, pl: 2.5, '& li': { mb: 1 } }}>
              <li>Откройте этот сайт в Safari.</li>
              <li>Нажмите <IosShareRoundedIcon fontSize="small" sx={{ verticalAlign: 'text-bottom' }} aria-label="Поделиться" /> «Поделиться».</li>
              <li>Выберите «На экран „Домой“» и нажмите «Добавить».</li>
            </Box>
          </>
        )}
      </Stack>
    </Paper>
  );
}

export function AppDownloadPage({ platform: forced }: { platform?: Platform }) {
  usePageTitle('Приложение для телефона');
  const [downloads, setDownloads] = useState<AppDownloads | null>(null);
  const [failed, setFailed] = useState(false);
  const platform = forced ?? detectPlatform(navigator.userAgent, navigator.maxTouchPoints);

  useEffect(() => {
    let active = true;
    fetch('/downloads/apps.json', { cache: 'no-store' })
      .then((response) => (response.ok ? response.json() : Promise.reject(new Error(String(response.status)))))
      .then((data: AppDownloads) => { if (active) setDownloads(data); })
      .catch(() => { if (active) setFailed(true); });
    return () => { active = false; };
  }, []);

  const cards = platform === 'ios'
    ? [<IosCard key="ios" downloads={downloads} platform={platform} />, <AndroidCard key="android" downloads={downloads} />]
    : [<AndroidCard key="android" downloads={downloads} />, <IosCard key="ios" downloads={downloads} platform={platform} />];

  return (
    <DocumentSurface kind="light">
      <Box sx={{ bgcolor: 'background.default', minHeight: '100vh', py: { xs: 2, md: 4 } }}>
        <Container maxWidth="lg">
          <Stack spacing={{ xs: 3, md: 4 }}>
            <BrandMark />
            <Stack direction="row" spacing={3} alignItems="center">
              <Box sx={{ minWidth: 0 }}>
                <Typography variant="h3" component="h1" fontWeight={800} sx={{ fontSize: { xs: 30, md: 44 } }}>Кабинет в телефоне</Typography>
                <Typography color="text.secondary" sx={{ mt: 1.5, fontSize: { xs: 16, md: 18 }, maxWidth: 640 }}>
                  Те же заявки, документы и подпись, что и на сайте: статус и напоминания всегда под рукой.
                </Typography>
              </Box>
              <Illustration name="mobile" size={150} sx={{ display: { xs: 'none', sm: 'block' } }} />
            </Stack>
            {failed && <Alert severity="warning">Не удалось загрузить ссылки на приложение. Обновите страницу чуть позже.</Alert>}
            <Grid container spacing={{ xs: 2, md: 3 }}>
              {cards.map((card, index) => <Grid item xs={12} md={6} key={index}>{card}</Grid>)}
            </Grid>
            {platform === 'desktop' && (
              <Paper variant="outlined" sx={{ p: { xs: 2.5, md: 3 }, borderRadius: 4, display: 'flex', gap: 3, alignItems: 'center', flexWrap: 'wrap' }}>
                <Box component="img" src="/downloads/qr-app.png" alt="QR-код страницы приложения" sx={{ width: 140, height: 140 }} />
                <Box sx={{ minWidth: 0, flex: 1 }}>
                  <Typography fontWeight={700}>Откройте страницу на телефоне</Typography>
                  <Typography color="text.secondary">Наведите камеру на QR-код — телефон сам предложит подходящий способ установки.</Typography>
                </Box>
              </Paper>
            )}
          </Stack>
        </Container>
      </Box>
    </DocumentSurface>
  );
}
