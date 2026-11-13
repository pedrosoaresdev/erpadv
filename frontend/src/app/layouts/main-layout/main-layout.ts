import { Component, computed, effect, inject, signal, DestroyRef } from '@angular/core';
import { BreakpointObserver } from '@angular/cdk/layout';
import { toSignal } from '@angular/core/rxjs-interop';
import { MatBadgeModule } from '@angular/material/badge';
import { MatButtonModule } from '@angular/material/button';
import { MatDividerModule } from '@angular/material/divider';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { MatTooltipModule } from '@angular/material/tooltip';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter, map } from 'rxjs';
import { AuthService } from '../../core/auth/auth.service';
import { AppNotification } from '../../core/models/models';
import { NotificationService } from '../../core/services/notification.service';
import { PushService } from '../../core/services/push.service';
import { ThemeService } from '../../core/services/theme.service';
import { RelativeTimePipe } from '../../shared/pipes/format.pipes';
import { GlobalSearchComponent } from './global-search';
import { IdleService } from '../../core/services/idle.service';
import { MOBILE_SHORTCUTS, NAV_SECTIONS, NavItem } from './nav-items';
import { OfficeService } from '../../modules/settings/office.service';

const COLLAPSE_KEY = 'erpadv.sidebar.collapsed';

@Component({
  selector: 'app-main-layout',
  imports: [
    RouterOutlet, RouterLink, RouterLinkActive, MatIconModule, MatButtonModule, MatMenuModule, MatBadgeModule,
    MatTooltipModule, MatDividerModule, GlobalSearchComponent, RelativeTimePipe,
  ],
  templateUrl: './main-layout.html',
  styleUrl: './main-layout.css',
})
export class MainLayoutComponent {
  readonly auth = inject(AuthService);
  readonly theme = inject(ThemeService);
  readonly notifications = inject(NotificationService);
  readonly push = inject(PushService);
  readonly office = inject(OfficeService);

  /** Aviso de assinatura para quem administra o escritório: avaliação terminando ou fatura em atraso. */
  readonly billingBanner = computed(() => {
    const s = this.office.status();
    if (!s || !this.auth.has('SETTINGS_MANAGE')) return null;
    if (s.subscriptionStatus === 'SUSPENSA') {
      return { tone: 'danger', text: 'Acesso da equipe suspenso por falta de pagamento. Após a confirmação do pagamento o acesso é liberado.' };
    }
    if (s.subscriptionStatus === 'EM_ATRASO') {
      return { tone: 'danger', text: 'Há fatura da assinatura vencida. Regularize para evitar a suspensão do acesso da equipe.' };
    }
    if (s.subscriptionStatus === 'TRIAL' && s.trialEndsAt) {
      const days = Math.ceil((new Date(`${s.trialEndsAt}T23:59:59`).getTime() - Date.now()) / 86400000);
      if (days <= 7) {
        return { tone: 'warning', text: days <= 0 ? 'O período de avaliação termina hoje.' : `O período de avaliação termina em ${days} dia(s).` };
      }
    }
    return null;
  });
  private readonly router = inject(Router);

  readonly isMobile = toSignal(
    inject(BreakpointObserver).observe('(max-width: 900px)').pipe(map((r) => r.matches)),
    { initialValue: false },
  );
  readonly collapsed = signal(this.readCollapsed());
  readonly mobileOpen = signal(false);
  readonly year = new Date().getFullYear();

  readonly sections = computed(() =>
    NAV_SECTIONS.map((s) => ({ ...s, items: s.items.filter((i) => this.allowed(i)) })).filter(
      (s) => s.items.length > 0,
    ),
  );

  /** Barra inferior do celular: 4 atalhos permitidos + botão "Menu" (abre a navegação completa). */
  readonly shortcuts = computed(() => MOBILE_SHORTCUTS.filter((i) => this.allowed(i)).slice(0, 4));

  private allowed(item: NavItem): boolean {
    // Itens sem permissão específica exigem pertencer a um escritório (ex.: notificações).
    if (item.anyPermission) return this.auth.hasAny(item.anyPermission);
    return item.permission ? this.auth.has(item.permission) : !!this.auth.me()?.officeId;
  }

  constructor() {
    effect(() => {
      const me = this.auth.me();
      if (me?.officeId) {
        this.notifications.start(me.officeId, me.uid);
        this.office.loadStatus();
        void this.push.init();
      } else {
        this.notifications.stop();
      }
    });
    this.router.events.pipe(filter((e) => e instanceof NavigationEnd)).subscribe(() => this.mobileOpen.set(false));
    inject(IdleService).start(inject(DestroyRef));
  }

  toggleSidebar(): void {
    if (this.isMobile()) {
      this.mobileOpen.update((v) => !v);
      return;
    }
    this.collapsed.update((v) => !v);
    try {
      localStorage.setItem(COLLAPSE_KEY, String(this.collapsed()));
    } catch {
      /* ignora */
    }
  }

  skipToContent(): void {
    document.getElementById('conteudo')?.focus();
  }

  phaseTooltip(item: NavItem): string {
    return `${item.label} — previsto para a Fase ${item.phase} do roadmap`;
  }

  openNotification(n: AppNotification): void {
    void this.notifications.markRead(n);
    if (n.link) {
      void this.router.navigateByUrl(n.link);
    }
  }

  async logout(): Promise<void> {
    this.notifications.stop();
    await this.push.disable();
    await this.auth.logout();
  }

  private readCollapsed(): boolean {
    try {
      return localStorage.getItem(COLLAPSE_KEY) === 'true';
    } catch {
      return false;
    }
  }
}
