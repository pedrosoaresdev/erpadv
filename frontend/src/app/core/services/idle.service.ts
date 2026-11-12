import { DestroyRef, Injectable, NgZone, effect, inject } from '@angular/core';
import { AuthService } from '../auth/auth.service';
import { ToastService } from './toast.service';

/**
 * Encerramento por inatividade conforme a política do escritório. O servidor também recusa sessões ociosas;
 * aqui o navegador sai na hora certa, sem esperar a próxima chamada.
 */
@Injectable({ providedIn: 'root' })
export class IdleService {
  private readonly auth = inject(AuthService);
  private readonly toast = inject(ToastService);
  private readonly zone = inject(NgZone);
  private last = Date.now();
  private timer: ReturnType<typeof setInterval> | null = null;
  private readonly bump = () => { this.last = Date.now(); };
  private readonly events = ['mousemove', 'keydown', 'click', 'touchstart', 'scroll'];

  start(destroy: DestroyRef): void {
    effect(() => {
      const minutes = this.auth.security()?.idleMinutes ?? 0;
      this.stop();
      if (minutes > 0) {
        this.watch(minutes);
      }
    });
    destroy.onDestroy(() => this.stop());
  }

  private watch(minutes: number): void {
    this.last = Date.now();
    this.zone.runOutsideAngular(() => {
      this.events.forEach((e) => window.addEventListener(e, this.bump, { passive: true }));
      this.timer = setInterval(() => {
        if (Date.now() - this.last > minutes * 60_000) {
          this.zone.run(() => {
            this.stop();
            this.toast.info('Sessão encerrada por inatividade.');
            void this.auth.logout();
          });
        }
      }, 15_000);
    });
  }

  private stop(): void {
    if (this.timer) {
      clearInterval(this.timer);
      this.timer = null;
    }
    this.events.forEach((e) => window.removeEventListener(e, this.bump));
  }
}
