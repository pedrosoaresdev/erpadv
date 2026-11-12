import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatTabsModule } from '@angular/material/tabs';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ActivatedRoute, Router } from '@angular/router';
import { ApiService } from '../../core/api/api.service';
import { AuthService } from '../../core/auth/auth.service';
import { MemberMfa, SecurityEvent, SecurityPolicy, SecuritySeverity, UserSessionView } from '../../core/models/models';
import { ToastService } from '../../core/services/toast.service';
import { ConfirmDialogComponent, ConfirmData } from '../../shared/components/confirm-dialog';
import { EmptyStateComponent, PageHeaderComponent } from '../../shared/components/ui';
import { BrDatePipe } from '../../shared/pipes/format.pipes';
import { BackupPanelComponent } from './backup-panel';
import { MfaSetupComponent, downloadRecoveryCodes } from './mfa';

type TabKey = 'conta' | 'equipe' | 'eventos' | 'politica' | 'backup';

const SEVERITY: Record<SecuritySeverity, string> = { INFO: 'Informativo', ATENCAO: 'Atenção', CRITICO: 'Crítico' };
const EVENT_LABELS: Record<string, string> = {
  SESSION_STARTED: 'Login', NEW_DEVICE: 'Dispositivo novo', SESSION_REVOKED: 'Sessão encerrada', SESSIONS_REVOKED: 'Sessões encerradas',
  MFA_ENABLED: '2FA ativado', MFA_DISABLED: '2FA desativado', MFA_FAILED: 'Código 2FA incorreto', MFA_LOCKED: '2FA bloqueado',
  MFA_RESET: '2FA redefinido', MFA_RECOVERY_USED: 'Código de recuperação usado', MFA_RECOVERY_REGENERATED: 'Novos códigos de recuperação',
  ACCESS_DENIED: 'Acesso negado', POLICY_CHANGED: 'Política alterada', BACKUP_CREATED: 'Backup gerado', BACKUP_FAILED: 'Falha no backup',
  BACKUP_DOWNLOADED: 'Backup baixado', BACKUP_RESTORED: 'Restauração de backup',
};

/** Segurança: autenticação em dois fatores, sessões e dispositivos, eventos de segurança, política e backup. */
@Component({
  selector: 'app-security-page',
  imports: [FormsModule, MatButtonModule, MatButtonToggleModule, MatIconModule, MatFormFieldModule, MatInputModule, MatProgressBarModule,
    MatSelectModule, MatSlideToggleModule, MatTabsModule, MatTooltipModule, PageHeaderComponent, EmptyStateComponent, BrDatePipe,
    MfaSetupComponent, BackupPanelComponent],
  template: `
    <div class="page">
      <app-page-header eyebrow="Administração" title="Segurança"
        subtitle="Autenticação em dois fatores, sessões e dispositivos, eventos de segurança, política do escritório e backup." />

      <mat-tab-group animationDuration="120ms" mat-stretch-tabs="false" [selectedIndex]="tabIndex()" (selectedIndexChange)="onTab($event)">
        <!-- ============================================================ minha conta -->
        <mat-tab label="Minha conta">
          <div class="grid">
            <section class="card pad">
              <h3><mat-icon>phonelink_lock</mat-icon>Autenticação em dois fatores</h3>
              @if (auth.security(); as s) {
                @if (s.mfaEnabled) {
                  <p class="ok"><mat-icon>verified_user</mat-icon>Ativa nesta conta. Cada login pede o código do aplicativo autenticador.</p>
                  @if (s.mfaRequired && !canDisable()) { <p class="muted small">A política do escritório exige o segundo fator para o seu perfil.</p> }
                  <div class="row">
                    <button mat-stroked-button (click)="regenerate()"><mat-icon>key</mat-icon>Gerar novos códigos de recuperação</button>
                    @if (canDisable()) { <button mat-button class="danger-text" (click)="disable()"><mat-icon>no_encryption</mat-icon>Desativar</button> }
                  </div>
                } @else {
                  @if (s.enrollmentRequired) { <p class="warn">O escritório exige o segundo fator para o seu perfil.</p> }
                  <app-mfa-setup (finished)="reloadStatus()" />
                }
              }
            </section>

            <section class="card pad">
              <div class="head"><h3><mat-icon>devices</mat-icon>Minhas sessões</h3><span class="spacer"></span>
                @if (mine().length > 1) { <button mat-stroked-button (click)="endOthers()"><mat-icon>logout</mat-icon>Encerrar as outras</button> }</div>
              @if (busy()) { <mat-progress-bar mode="indeterminate" /> }
              <ul class="sessions">
                @for (x of mine(); track x.id) {
                  <li>
                    <mat-icon>{{ x.mobile ? 'smartphone' : 'computer' }}</mat-icon>
                    <div class="grow">
                      <strong>{{ x.device }}</strong> @if (x.current) { <span class="badge success">esta sessão</span> }
                      @if (x.newDevice) { <span class="badge warn">dispositivo novo</span> }
                      <div class="muted small">Entrou em {{ x.authTime | brDate: true }} · última atividade {{ x.lastSeenAt | brDate: true }}{{ x.ip ? ' · IP ' + x.ip : '' }}</div>
                    </div>
                    @if (!x.current) { <button mat-icon-button matTooltip="Encerrar sessão" (click)="endMine(x)" aria-label="Encerrar sessão"><mat-icon>close</mat-icon></button> }
                  </li>
                } @empty { <li class="muted">Nenhuma sessão ativa.</li> }
              </ul>
              <p class="muted small">Se não reconhecer um acesso, encerre a sessão e troque a senha.</p>
            </section>
          </div>
        </mat-tab>

        <!-- ============================================================ equipe -->
        @if (canTeam()) {
          <mat-tab label="Equipe">
            <section class="card">
              <div class="head pad"><h3><mat-icon>groups</mat-icon>Sessões ativas da equipe</h3><span class="spacer"></span>
                <button mat-button (click)="loadTeam()"><mat-icon>refresh</mat-icon>Atualizar</button></div>
              <div class="table-wrap">
                <table class="data stack">
                  <thead><tr><th>Integrante</th><th>Dispositivo</th><th>Login</th><th>Última atividade</th><th>IP</th><th>2FA</th><th></th></tr></thead>
                  <tbody>
                    @for (x of team(); track x.id) {
                      <tr>
                        <td data-label="Integrante"><strong>{{ x.userName }}</strong>@if (x.current) { <span class="badge success">você</span> }</td>
                        <td data-label="Dispositivo">{{ x.device }}@if (x.newDevice) { <span class="badge warn">novo</span> }</td>
                        <td data-label="Login">{{ x.authTime | brDate: true }}</td>
                        <td data-label="Última atividade">{{ x.lastSeenAt | brDate: true }}</td>
                        <td data-label="IP" class="mono">{{ x.ip || '—' }}</td>
                        <td data-label="2FA">{{ x.mfaVerified ? 'confirmado' : '—' }}</td>
                        <td>@if (!x.current) { <button mat-stroked-button (click)="endTeam(x)"><mat-icon>logout</mat-icon>Encerrar</button> }</td>
                      </tr>
                    } @empty { <tr><td colspan="7" class="muted center">Nenhuma sessão ativa.</td></tr> }
                  </tbody>
                </table>
              </div>
            </section>
            <section class="card">
              <div class="head pad"><h3><mat-icon>phonelink_lock</mat-icon>Segundo fator por integrante</h3></div>
              <div class="table-wrap">
                <table class="data stack">
                  <thead><tr><th>Integrante</th><th>Cargo</th><th>2FA</th><th></th></tr></thead>
                  <tbody>
                    @for (m of members(); track m.uid) {
                      <tr>
                        <td data-label="Integrante"><strong>{{ m.name }}</strong><div class="muted small">{{ m.email }}</div></td>
                        <td data-label="Cargo">{{ m.role || '—' }}</td>
                        <td data-label="2FA">
                          @if (m.mfaEnabled) { <span class="badge success">ativo</span> <span class="muted small">desde {{ m.mfaEnabledAt | brDate }}</span> }
                          @else { <span class="badge">inativo</span> }
                        </td>
                        <td>@if (m.mfaEnabled && canReset() && m.uid !== auth.me()?.uid) {
                          <button mat-stroked-button (click)="resetMfa(m)" matTooltip="Para quem perdeu o celular: remove o 2FA e encerra as sessões"><mat-icon>restart_alt</mat-icon>Redefinir</button> }</td>
                      </tr>
                    }
                  </tbody>
                </table>
              </div>
            </section>
          </mat-tab>
        }

        <!-- ============================================================ eventos -->
        @if (canEvents()) {
          <mat-tab label="Eventos de segurança">
            <section class="card">
              <div class="head pad">
                <mat-button-toggle-group [(ngModel)]="severity" (change)="loadEvents()" aria-label="Gravidade">
                  <mat-button-toggle [value]="null">Todos</mat-button-toggle>
                  <mat-button-toggle value="CRITICO">Críticos</mat-button-toggle>
                  <mat-button-toggle value="ATENCAO">Atenção</mat-button-toggle>
                  <mat-button-toggle value="INFO">Informativos</mat-button-toggle>
                </mat-button-toggle-group>
                <span class="spacer"></span>
                <button mat-button (click)="loadEvents()"><mat-icon>refresh</mat-icon>Atualizar</button>
              </div>
              @if (events().length === 0) { <app-empty-state icon="shield" title="Nenhum evento" /> } @else {
                <div class="table-wrap">
                  <table class="data stack">
                    <thead><tr><th>Quando</th><th>Gravidade</th><th>Evento</th><th>Descrição</th><th>IP</th></tr></thead>
                    <tbody>
                      @for (e of events(); track e.id) {
                        <tr>
                          <td data-label="Quando" class="nowrap">{{ e.at | brDate: true }}</td>
                          <td data-label="Gravidade"><span class="badge" [class.danger]="e.severity === 'CRITICO'" [class.warn]="e.severity === 'ATENCAO'">{{ severityLabel[e.severity] }}</span></td>
                          <td data-label="Evento">{{ eventLabel(e.type) }}</td>
                          <td data-label="Descrição">{{ e.description }}</td>
                          <td data-label="IP" class="mono">{{ e.ip || '—' }}</td>
                        </tr>
                      }
                    </tbody>
                  </table>
                </div>
              }
            </section>
          </mat-tab>
        }

        <!-- ============================================================ política -->
        @if (canEvents()) {
          <mat-tab label="Política">
            @if (policy(); as p) {
              <section class="card pad policy">
                <h3><mat-icon>policy</mat-icon>Política de segurança do escritório</h3>
                <mat-form-field appearance="outline">
                  <mat-label>Exigir autenticação em dois fatores</mat-label>
                  <mat-select [(ngModel)]="p.mfaRequirement" [disabled]="!canPolicy()">
                    <mat-option value="NENHUM">Não exigir (cada um escolhe)</mat-option>
                    <mat-option value="ADMINISTRADORES">Administradores e quem gerencia a equipe</mat-option>
                    <mat-option value="EQUIPE">Toda a equipe</mat-option>
                  </mat-select>
                  <mat-hint>Quem ainda não configurou será levado à configuração no próximo acesso.</mat-hint>
                </mat-form-field>
                <div class="two">
                  <mat-form-field appearance="outline">
                    <mat-label>Duração máxima da sessão (horas)</mat-label>
                    <input matInput type="number" min="0" max="720" [(ngModel)]="p.sessionMaxHours" [disabled]="!canPolicy()" />
                    <mat-hint>0 = sem limite. Depois disso é preciso entrar de novo.</mat-hint>
                  </mat-form-field>
                  <mat-form-field appearance="outline">
                    <mat-label>Encerrar por inatividade (minutos)</mat-label>
                    <input matInput type="number" min="0" max="1440" [(ngModel)]="p.idleMinutes" [disabled]="!canPolicy()" />
                    <mat-hint>0 = desativado; mínimo de 5 minutos.</mat-hint>
                  </mat-form-field>
                </div>
                <mat-slide-toggle [(ngModel)]="p.requireJustificationOnDelete" [disabled]="!canPolicy()">
                  Exigir justificativa em exclusões (registrada na auditoria)
                </mat-slide-toggle>
                <mat-slide-toggle [(ngModel)]="p.newDeviceAlert" [disabled]="!canPolicy()">
                  Avisar o usuário quando entrar por um dispositivo novo
                </mat-slide-toggle>
                <h4>Backup automático</h4>
                <div class="two">
                  <mat-slide-toggle [(ngModel)]="p.backupEnabled" [disabled]="!canPolicy()">Backup diário (02:30)</mat-slide-toggle>
                  <mat-form-field appearance="outline">
                    <mat-label>Manter backups por (dias)</mat-label>
                    <input matInput type="number" min="7" max="365" [(ngModel)]="p.backupRetentionDays" [disabled]="!canPolicy()" />
                  </mat-form-field>
                </div>
                @if (p.updatedByName) { <p class="muted small">Última alteração por {{ p.updatedByName }} em {{ p.updatedAt | brDate: true }}.</p> }
                @if (canPolicy()) {
                  <div class="row"><span class="spacer"></span><button mat-flat-button (click)="savePolicy(p)" [disabled]="busy()"><mat-icon>save</mat-icon>Salvar política</button></div>
                }
              </section>
            }
          </mat-tab>
        }

        <!-- ============================================================ backup -->
        @if (canPolicy()) {
          <mat-tab label="Backup">
            <app-backup-panel />
          </mat-tab>
        }
      </mat-tab-group>
    </div>
  `,
  styles: `
    .grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; padding-top: 16px; }
    .card { margin-top: 16px; }
    .grid .card { margin-top: 0; }
    .pad { padding: 16px 20px; }
    h3 { display: flex; align-items: center; gap: 8px; margin: 0 0 12px; font-size: 16px; }
    h4 { margin: 16px 0 4px; }
    .head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
    .head h3 { margin: 0; }
    .row { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; margin-top: 8px; }
    .sessions { list-style: none; margin: 8px 0; padding: 0; }
    .sessions li { display: flex; gap: 12px; align-items: center; padding: 10px 0; border-bottom: 1px solid var(--app-border); }
    .grow { flex: 1; min-width: 0; }
    .ok { display: flex; gap: 8px; align-items: center; color: var(--app-success, #2e7d32); }
    .warn { color: var(--app-warning, #b26a00); }
    .danger-text { color: var(--app-danger, #b42318); }
    .badge.warn { background: var(--app-warning-bg, #fff4e5); color: var(--app-warning, #b26a00); }
    .badge.success { background: var(--app-success-bg); color: var(--app-success); }
    .badge.danger { background: var(--app-danger-bg); color: var(--app-danger); }
    .small { font-size: 12px; }
    .nowrap { white-space: nowrap; }
    .center { text-align: center; }
    .policy { display: flex; flex-direction: column; gap: 12px; max-width: 760px; }
    .two { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; align-items: center; }
    @media (max-width: 900px) { .grid, .two { grid-template-columns: minmax(0, 1fr); } }
  `,
})
export class SecurityPageComponent {
  readonly auth = inject(AuthService);
  private readonly api = inject(ApiService);
  private readonly toast = inject(ToastService);
  private readonly dialog = inject(MatDialog);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  readonly severityLabel = SEVERITY;
  readonly busy = signal(false);
  readonly mine = signal<UserSessionView[]>([]);
  readonly team = signal<UserSessionView[]>([]);
  readonly members = signal<MemberMfa[]>([]);
  readonly events = signal<SecurityEvent[]>([]);
  readonly policy = signal<SecurityPolicy | null>(null);
  severity: SecuritySeverity | null = null;

  readonly canTeam = computed(() => this.auth.hasAny(['EMPLOYEES_MANAGE', 'SETTINGS_MANAGE']));
  readonly canReset = computed(() => this.auth.has('EMPLOYEES_MANAGE'));
  readonly canEvents = computed(() => this.auth.hasAny(['SETTINGS_MANAGE', 'AUDIT_READ']));
  readonly canPolicy = computed(() => this.auth.has('SETTINGS_MANAGE'));
  /** Quem a política obriga não pode desativar o 2FA (o servidor também recusa). */
  readonly canDisable = computed(() => !!this.auth.security()?.mfaEnabled && !this.policyRequiresMe());
  readonly tabs = computed<TabKey[]>(() => [
    'conta',
    ...(this.canTeam() ? ['equipe' as TabKey] : []),
    ...(this.canEvents() ? ['eventos' as TabKey, 'politica' as TabKey] : []),
    ...(this.canPolicy() ? ['backup' as TabKey] : []),
  ]);
  readonly tabIndex = signal(0);

  constructor() {
    const aba = this.route.snapshot.queryParamMap.get('aba') as TabKey | null;
    const i = aba ? this.tabs().indexOf(aba) : 0;
    this.tabIndex.set(Math.max(0, i));
    this.reloadStatus();
    this.loadMine();
    if (this.canTeam()) this.loadTeam();
    if (this.canEvents()) {
      this.loadEvents();
      this.api.get<SecurityPolicy>('/security/policy').subscribe((p) => this.policy.set({ ...p }));
    }
  }

  onTab(i: number): void {
    this.tabIndex.set(i);
    void this.router.navigate([], { queryParams: { aba: this.tabs()[i] }, replaceUrl: true });
  }

  eventLabel(type: string): string {
    return EVENT_LABELS[type] ?? type;
  }

  reloadStatus(): void {
    void this.auth.loadSecurity();
    this.loadMine();
  }

  policyRequiresMe(): boolean {
    const p = this.policy();
    if (!p || p.mfaRequirement === 'NENHUM') return false;
    if (p.mfaRequirement === 'EQUIPE') return true;
    return this.auth.hasAny(['SETTINGS_MANAGE', 'EMPLOYEES_MANAGE']) || this.auth.me()?.role === 'SOCIO_ADMIN';
  }

  loadMine(): void {
    this.api.get<UserSessionView[]>('/security/sessions/mine').subscribe((l) => this.mine.set(l));
  }

  loadTeam(): void {
    this.api.get<UserSessionView[]>('/security/sessions').subscribe((l) => this.team.set(l));
    this.api.get<MemberMfa[]>('/security/users/mfa').subscribe((l) => this.members.set(l));
  }

  loadEvents(): void {
    this.api.get<SecurityEvent[]>('/security/events', { severity: this.severity, size: 300 }).subscribe((l) => this.events.set(l));
  }

  private askCode(title: string, message: string, confirmLabel: string, danger = false): Promise<string | null> {
    const data: ConfirmData = { title, message, reasonLabel: 'Código do aplicativo (ou de recuperação)', reasonRequired: true, confirmLabel, danger };
    return new Promise((resolve) => this.dialog.open(ConfirmDialogComponent, { data, width: '460px', maxWidth: '96vw' })
      .afterClosed().subscribe((r) => resolve(typeof r === 'string' && r.trim() ? r.trim() : null)));
  }

  async disable(): Promise<void> {
    const code = await this.askCode('Desativar o segundo fator', 'Sua conta voltará a pedir apenas a senha. Confirme com um código.', 'Desativar', true);
    if (!code) return;
    this.api.post<void>('/security/mfa/disable', { code }).subscribe(() => {
      this.toast.success('Autenticação em dois fatores desativada.');
      this.reloadStatus();
    });
  }

  async regenerate(): Promise<void> {
    const code = await this.askCode('Novos códigos de recuperação', 'Os códigos antigos deixarão de valer. Confirme com um código do aplicativo.', 'Gerar');
    if (!code) return;
    this.api.post<{ recoveryCodes: string[] }>('/security/mfa/recovery-codes', { code }).subscribe((r) => {
      downloadRecoveryCodes(r.recoveryCodes, this.auth.me()?.email ?? '');
      this.toast.success('Novos códigos gerados e baixados (arquivo .txt).');
    });
  }

  endMine(x: UserSessionView): void {
    this.api.delete<void>(`/security/sessions/mine/${x.id}`).subscribe(() => { this.toast.success('Sessão encerrada.'); this.loadMine(); });
  }

  endOthers(): void {
    this.api.post<{ ended: number }>('/security/sessions/mine/end-others', {}).subscribe((r) => {
      this.toast.success(`${r.ended} sessão(ões) encerrada(s).`);
      this.loadMine();
    });
  }

  endTeam(x: UserSessionView): void {
    const data: ConfirmData = { title: 'Encerrar sessão', message: `Encerrar o acesso de ${x.userName} em ${x.device}? A pessoa precisará entrar de novo.`,
      confirmLabel: 'Encerrar', danger: true };
    this.dialog.open(ConfirmDialogComponent, { data }).afterClosed().subscribe((ok) => {
      if (!ok) return;
      this.api.delete<void>(`/security/sessions/${x.id}`).subscribe(() => { this.toast.success('Sessão encerrada.'); this.loadTeam(); });
    });
  }

  resetMfa(m: MemberMfa): void {
    const data: ConfirmData = { title: 'Redefinir segundo fator', danger: true, confirmLabel: 'Redefinir',
      message: `Remove o 2FA de ${m.name} e encerra todas as sessões. Use quando a pessoa perder o celular. Ela configurará de novo no próximo acesso.` };
    this.dialog.open(ConfirmDialogComponent, { data }).afterClosed().subscribe((ok) => {
      if (!ok) return;
      this.api.post<void>(`/security/users/${m.uid}/mfa/reset`, {}).subscribe(() => { this.toast.success('Segundo fator redefinido.'); this.loadTeam(); });
    });
  }

  savePolicy(p: SecurityPolicy): void {
    this.busy.set(true);
    this.api.put<SecurityPolicy>('/security/policy', {
      requireJustificationOnDelete: p.requireJustificationOnDelete, mfaRequirement: p.mfaRequirement,
      sessionMaxHours: Number(p.sessionMaxHours) || 0, idleMinutes: Number(p.idleMinutes) || 0, newDeviceAlert: p.newDeviceAlert,
      backupEnabled: p.backupEnabled, backupRetentionDays: Number(p.backupRetentionDays) || 30,
    }).subscribe({
      next: (saved) => {
        this.busy.set(false);
        this.policy.set({ ...saved });
        this.toast.success('Política de segurança salva.');
        void this.auth.loadSecurity();
        this.loadEvents();
      },
      error: () => this.busy.set(false),
    });
  }
}
