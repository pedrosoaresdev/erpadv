import { provideHttpClient, withInterceptors } from '@angular/common/http';
import {
  ApplicationConfig,
  LOCALE_ID,
  inject,
  provideAppInitializer,
  provideBrowserGlobalErrorListeners,
  provideZoneChangeDetection,
} from '@angular/core';
import { registerLocaleData } from '@angular/common';
import localePt from '@angular/common/locales/pt';
import { MAT_FORM_FIELD_DEFAULT_OPTIONS } from '@angular/material/form-field';
import { MatIconRegistry } from '@angular/material/icon';
import { provideAnimationsAsync } from '@angular/platform-browser/animations/async';
import { provideRouter, withComponentInputBinding, withInMemoryScrolling } from '@angular/router';
import { routes } from './app.routes';
import { authInterceptor, errorInterceptor } from './core/auth/interceptors';
import { ConfigService } from './core/config/config.service';
import { FirebaseService } from './core/config/firebase.service';

registerLocaleData(localePt);

/** Carrega /config.json e inicializa o Firebase antes da aplicação. */
async function bootstrap(): Promise<void> {
  const config = inject(ConfigService);
  const firebase = inject(FirebaseService);
  inject(MatIconRegistry).setDefaultFontSetClass('material-icons-outlined');
  await config.load();
  await firebase.init();
}

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideZoneChangeDetection({ eventCoalescing: true }),
    provideRouter(routes, withComponentInputBinding(), withInMemoryScrolling({ scrollPositionRestoration: 'top' })),
    provideHttpClient(withInterceptors([authInterceptor, errorInterceptor])),
    provideAnimationsAsync(),
    provideAppInitializer(bootstrap),
    { provide: LOCALE_ID, useValue: 'pt-BR' },
    { provide: MAT_FORM_FIELD_DEFAULT_OPTIONS, useValue: { appearance: 'outline' } },
  ],
};
