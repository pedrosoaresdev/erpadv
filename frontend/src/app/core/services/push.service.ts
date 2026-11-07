import { Injectable, inject, signal } from '@angular/core';
import { getMessaging, getToken, isSupported, onMessage } from 'firebase/messaging';
import { firstValueFrom } from 'rxjs';
import { ApiService } from '../api/api.service';
import { ConfigService } from '../config/config.service';
import { FirebaseService } from '../config/firebase.service';
import { ToastService } from './toast.service';

const TOKEN_KEY = 'erpadv.fcmToken';

/**
 * Notificações push via Firebase Cloud Messaging. Disponível quando o projeto Firebase real está
 * configurado com chave VAPID (no Emulator Suite o FCM não é emulado; as notificações continuam
 * chegando em tempo real pelo sino).
 */
@Injectable({ providedIn: 'root' })
export class PushService {
  private readonly config = inject(ConfigService);
  private readonly firebase = inject(FirebaseService);
  private readonly api = inject(ApiService);
  private readonly toast = inject(ToastService);

  readonly available = signal(false);
  readonly enabled = signal(false);
  private listening = false;

  async init(): Promise<void> {
    const vapidKey = this.config.value.messaging?.vapidKey;
    const supported = !!vapidKey && !this.config.value.emulators?.authUrl && (await isSupported().catch(() => false));
    this.available.set(supported);
    if (supported && typeof Notification !== 'undefined' && Notification.permission === 'granted') {
      await this.register().catch(() => undefined);
    }
  }

  async enable(): Promise<void> {
    if (!this.available()) return;
    const permission = await Notification.requestPermission();
    if (permission !== 'granted') {
      this.toast.error('Permissão de notificação negada no navegador.');
      return;
    }
    await this.register();
    this.toast.success('Notificações push ativadas neste navegador.');
  }

  private async register(): Promise<void> {
    const cfg = this.config.value;
    const fb = cfg.firebase;
    const query = new URLSearchParams({
      apiKey: fb.apiKey ?? '', projectId: fb.projectId ?? '', messagingSenderId: fb.messagingSenderId ?? '', appId: fb.appId ?? '',
    });
    const registration = await navigator.serviceWorker.register(`/firebase-messaging-sw.js?${query}`);
    const messaging = getMessaging(this.firebase.app);
    const token = await getToken(messaging, { vapidKey: cfg.messaging?.vapidKey, serviceWorkerRegistration: registration });
    if (!token) return;
    await firstValueFrom(this.api.post('/notifications/devices', { token, platform: 'WEB' }, true));
    try {
      localStorage.setItem(TOKEN_KEY, token);
    } catch {
      /* ignora */
    }
    this.enabled.set(true);
    if (!this.listening) {
      this.listening = true;
      onMessage(messaging, (payload) => {
        if (payload.notification?.title) this.toast.info(payload.notification.title);
      });
    }
  }

  /** Remove o token deste navegador (chamado no logout). */
  async disable(): Promise<void> {
    let token: string | null = null;
    try {
      token = localStorage.getItem(TOKEN_KEY);
      localStorage.removeItem(TOKEN_KEY);
    } catch {
      /* ignora */
    }
    this.enabled.set(false);
    if (token) {
      await firstValueFrom(this.api.post('/notifications/devices/remove', { token, platform: 'WEB' }, true)).catch(() => undefined);
    }
  }
}
