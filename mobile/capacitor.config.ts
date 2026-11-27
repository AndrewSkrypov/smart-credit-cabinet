import type { CapacitorConfig } from '@capacitor/cli';

// The shell opens the cabinet as its own site: same origin, no CORS, always the current stand.
// CABINET_APP_URL points the build at another stand, for example http://<mac-ip>:8080 in a local network.
const url = process.env.CABINET_APP_URL ?? 'https://credit-cabinet.ru';

const config: CapacitorConfig = {
  appId: 'ru.creditcabinet.pump',
  appName: 'Кабинет',
  webDir: 'www',
  server: {
    url,
    cleartext: url.startsWith('http://'),
    errorPath: 'offline.html',
  },
  ios: {
    contentInset: 'never',
    backgroundColor: '#1f2a44',
  },
  android: {
    backgroundColor: '#1f2a44',
  },
  plugins: {
    SplashScreen: {
      launchShowDuration: 900,
      backgroundColor: '#1f2a44',
      showSpinner: false,
    },
    StatusBar: {
      overlaysWebView: false,
      style: 'LIGHT',
      backgroundColor: '#1f2a44',
    },
  },
};

export default config;
