import { Component, inject, signal } from '@angular/core';
import { Clipboard } from '@angular/cdk/clipboard';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTabsModule } from '@angular/material/tabs';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';
import { Employee } from '../../core/models/models';
import { ToastService } from '../../core/services/toast.service';
import { ConfirmDialogComponent } from '../../shared/components/confirm-dialog';
import { EmptyStateComponent, PageHeaderComponent } from '../../shared/components/ui';
import { USER_STATUS_LABELS } from '../../shared/labels';
import { RelativeTimePipe } from '../../shared/pipes/format.pipes';
import { CasesService } from '../cases/cases.service';
import { PasswordDialogComponent } from '../../shared/components/password-dialog';
import { EmployeeEditDialogComponent, InviteDialogComponent, RoleDialogComponent } from './employee-dialogs';
import { CustomRole, EmployeesService } from './employees.service';

@Component({
  selector: 'app-employees-list',
  imports: [MatButtonModule, MatIconModule, MatMenuModule, MatProgressBarModule, MatTabsModule, RouterLink, PageHeaderComponent, EmptyStateComponent, RelativeTimePipe],
  template: `
    <div class="page">
      <app-page-header eyebrow="Administração" title="Equipe" subtitle="Usuários, cargos e permissões do escritório.">
        @if (auth.has('EMPLOYEES_MANAGE')) {
          <button mat-stroked-button (click)="newRole()"><mat-icon>workspace_premium</mat-icon>Novo cargo</button>
          <button mat-flat-button (click)="invite()"><mat-icon>person_add</mat-icon>Novo integrante</button>
        }
      </app-page-header>

      <mat-tab-group animationDuration="150ms" mat-stretch-tabs="false" [selectedIndex]="tab()" (selectedIndexChange)="selectTab($event)">
      <mat-tab [label]="'Integrantes (' + items().length + ')'">
      <div class="card tab-card">
        @if (loading()) { <mat-progress-bar mode="indeterminate" /> }
        @if (!loading() && items().length === 0) {
          <app-empty-state icon="badge" title="Nenhum integrante" />
        } @else {
          <div class="table-wrap">
            <table class="data">
              <thead><tr><th>Integrante</th><th>Papel</th><th>OAB</th><th>Situação</th><th>Último acesso</th><th></th></tr></thead>
              <tbody>
                @for (e of items(); track e.uid) {
                  <tr>
                    <td>
                      <div class="who">
                        <span class="av">{{ initials(e.displayName) }}</span>
                        <div><div class="name">{{ e.displayName }}@if (e.uid === auth.me()?.uid) { <span class="muted"> (você)</span> }</div><div class="muted small">{{ e.email }}</div></div>
                      </div>
                    </td>
                    <td>
                      @if (e.customRoleName) {
                        <span class="role-chip" [style.borderColor]="roleColor(e.customRoleId)"><span class="dot" [style.background]="roleColor(e.customRoleId)"></span>{{ e.customRoleName }}</span>
                      } @else { {{ e.roleLabel }} }
                      @if (e.permissionsGranted.length || e.permissionsRevoked.length) { <span class="badge gold" title="Permissões ajustadas individualmente">ajustado</span> }
                    </td>
                    <td>{{ e.oabNumber || '—' }}</td>
                    <td><span class="badge" [class.success]="e.status === 'ACTIVE'" [class.danger]="e.status === 'BLOCKED'" [class.info]="e.status === 'INVITED'">{{ status[e.status] }}</span></td>
                    <td class="muted">{{ e.lastLoginAt ? (e.lastLoginAt | relative) : 'Nunca acessou' }}</td>
                    <td class="actions">
                      @if (auth.has('EMPLOYEES_MANAGE') && e.uid !== auth.me()?.uid) {
                        <button mat-icon-button [matMenuTriggerFor]="menu" aria-label="Ações"><mat-icon>more_vert</mat-icon></button>
                        <mat-menu #menu="matMenu" xPosition="before">
                          <button mat-menu-item (click)="edit(e)"><mat-icon>manage_accounts</mat-icon>Cargo e permissões</button>
                          <button mat-menu-item (click)="setPassword(e)"><mat-icon>password</mat-icon>Definir nova senha</button>
                          <button mat-menu-item (click)="reset(e)"><mat-icon>key</mat-icon>Gerar link de redefinição de senha</button>
                          <button mat-menu-item (click)="revoke(e)"><mat-icon>logout</mat-icon>Encerrar sessões ativas</button>
                          @if (auth.has('AUDIT_READ')) {
                            <a mat-menu-item [routerLink]="['/auditoria']" [queryParams]="{ ator: e.uid }"><mat-icon>history</mat-icon>Histórico de atividades</a>
                          }
                          @if (e.status === 'BLOCKED') {
                            <button mat-menu-item (click)="unblock(e)"><mat-icon>lock_open</mat-icon>Desbloquear</button>
                          } @else {
                            <button mat-menu-item (click)="block(e)" class="danger"><mat-icon>block</mat-icon>Bloquear acesso</button>
                          }
                        </mat-menu>
                      }
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
          @if (cursor()) { <div class="more"><button mat-stroked-button (click)="load(true)">Carregar mais</button></div> }
        }
      </div>
      </mat-tab>

      <mat-tab [label]="'Cargos (' + customRoles().length + ')'">
        <div class="roles">
          @for (r of customRoles(); track r.id) {
            <div class="card role-card" [style.borderTopColor]="r.color || '#7a8ca8'">
              <div class="row">
                <span class="dot big" [style.background]="r.color || '#7a8ca8'"></span>
                <div class="grow">
                  <div class="name">{{ r.name }}</div>
                  <div class="muted small">comporta-se como {{ r.baseRoleLabel }}</div>
                </div>
                @if (auth.has('EMPLOYEES_MANAGE')) {
                  <button mat-icon-button [matMenuTriggerFor]="rmenu" aria-label="Ações do cargo"><mat-icon>more_vert</mat-icon></button>
                  <mat-menu #rmenu="matMenu" xPosition="before">
                    <button mat-menu-item (click)="editRole(r)"><mat-icon>edit</mat-icon>Editar permissões</button>
                    <button mat-menu-item class="danger" (click)="deleteRole(r)" [disabled]="r.users > 0"><mat-icon>delete</mat-icon>Excluir{{ r.users > 0 ? ' (em uso)' : '' }}</button>
                  </mat-menu>
                }
              </div>
              @if (r.description) { <p class="muted desc">{{ r.description }}</p> }
              <div class="meta">
                <span><mat-icon>group</mat-icon>{{ r.users }} integrante(s)</span>
                <span><mat-icon>verified</mat-icon>{{ r.permissions.length }} permissões</span>
              </div>
            </div>
          } @empty {
            <div class="card tab-card">
              <app-empty-state icon="workspace_premium" title="Nenhum cargo personalizado"
                text="Crie cargos como Advogado júnior, Estagiário, Secretário(a) ou Financeiro e escolha exatamente o que cada um acessa." />
              @if (auth.has('EMPLOYEES_MANAGE')) { <div class="more"><button mat-flat-button (click)="newRole()"><mat-icon>add</mat-icon>Criar primeiro cargo</button></div> }
            </div>
          }
        </div>
      </mat-tab>
      </mat-tab-group>
    </div>
  `,
  styles: `
    .who { display: flex; gap: 12px; align-items: center; }
    .av { width: 36px; height: 36px; border-radius: 10px; display: grid; place-items: center; font-weight: 700; font-size: 13px;
      background: linear-gradient(135deg, var(--app-navy), var(--app-navy-2)); color: var(--app-gold); }
    .name { font-weight: 600; }
    .small { font-size: 12px; }
    .actions { text-align: right; width: 56px; }
    .danger { color: var(--app-danger); }
    .badge.gold { margin-left: 6px; }
    .more { display: flex; justify-content: center; padding: 16px; }
    .tab-card { margin-top: 16px; }
    .roles { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 16px; margin-top: 16px; }
    .role-card { padding: 16px 18px; border-top: 4px solid; }
    .grow { flex: 1; min-width: 0; }
    .desc { font-size: 13px; margin: 10px 0 0; }
    .meta { display: flex; gap: 16px; margin-top: 12px; font-size: 13px; color: var(--app-text-muted); }
    .meta span { display: inline-flex; gap: 4px; align-items: center; }
    .meta mat-icon { font-size: 16px; width: 16px; height: 16px; }
    .dot { display: inline-block; width: 9px; height: 9px; border-radius: 50%; margin-right: 6px; }
    .dot.big { width: 14px; height: 14px; margin: 0; }
    .role-chip { display: inline-flex; align-items: center; border: 1px solid; border-radius: 999px; padding: 2px 10px; font-size: 13px; }
  `,
})
export class EmployeesListComponent {
  readonly auth = inject(AuthService);
  private readonly service = inject(EmployeesService);
  private readonly cases = inject(CasesService);
  private readonly dialog = inject(MatDialog);
  private readonly toast = inject(ToastService);
  private readonly clipboard = inject(Clipboard);
  readonly status = USER_STATUS_LABELS;
  readonly items = signal<Employee[]>([]);
  readonly cursor = signal<string | null>(null);
  readonly loading = signal(false);

  readonly customRoles = signal<CustomRole[]>([]);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  /** Aba ativa vinculada à URL (?aba=cargos), usada pelo item "Permissões" do menu. */
  readonly tab = signal(0);

  selectTab(index: number): void {
    this.tab.set(index);
    void this.router.navigate([], { queryParams: { aba: index === 1 ? 'cargos' : null }, queryParamsHandling: 'merge', replaceUrl: true });
  }

  constructor() {
    this.route.queryParamMap.subscribe((q) => this.tab.set(q.get('aba') === 'cargos' ? 1 : 0));
    this.load(false);
    this.loadRoles();
  }

  loadRoles(): void {
    this.service.customRoles().subscribe((r) => this.customRoles.set(r));
  }

  roleColor(id: string | null): string {
    return this.customRoles().find((r) => r.id === id)?.color || '#7a8ca8';
  }

  newRole(): void {
    this.dialog.open(RoleDialogComponent, { data: null, width: '900px', maxWidth: '96vw', disableClose: true })
      .afterClosed().subscribe((r?: CustomRole) => {
        if (r) {
          this.toast.success(`Cargo ${r.name} criado.`);
          this.loadRoles();
        }
      });
  }

  editRole(role: CustomRole): void {
    this.dialog.open(RoleDialogComponent, { data: role, width: '900px', maxWidth: '96vw' })
      .afterClosed().subscribe((r?: CustomRole) => {
        if (r) {
          this.toast.success(`Cargo ${r.name} atualizado${r.users ? ' — aplicado a ' + r.users + ' integrante(s)' : ''}.`);
          this.loadRoles();
          this.load(false);
        }
      });
  }

  deleteRole(role: CustomRole): void {
    this.confirm('Excluir cargo', `O cargo ${role.name} será excluído.`, 'Excluir', true).subscribe((ok) => {
      if (ok) this.service.deleteRole(role.id).subscribe(() => { this.toast.success('Cargo excluído.'); this.loadRoles(); });
    });
  }

  setPassword(e: Employee): void {
    this.dialog.open(PasswordDialogComponent, { data: { title: `Nova senha · ${e.displayName}`, subtitle: e.email }, width: '480px' })
      .afterClosed().subscribe((password?: string) => {
        if (password) {
          this.service.setPassword(e.uid, password).subscribe((u) => { this.replace(u); this.toast.success('Senha definida e sessões encerradas.'); });
        }
      });
  }

  initials(name: string): string {
    return name.split(/\s+/).filter(Boolean).slice(0, 2).map((p) => p[0].toUpperCase()).join('');
  }

  load(more: boolean): void {
    this.loading.set(true);
    this.service.list(more ? this.cursor() : null).subscribe({
      next: (page) => {
        this.items.set(more ? [...this.items(), ...page.items] : page.items);
        this.cursor.set(page.nextCursor);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  private replace(e: Employee): void {
    this.items.set(this.items().map((x) => (x.uid === e.uid ? e : x)));
    this.cases.invalidateLawyers();
  }

  invite(): void {
    this.dialog.open(InviteDialogComponent, { width: '720px', maxWidth: '96vw', disableClose: true }).afterClosed().subscribe((done) => {
      if (done) {
        this.load(false);
        this.loadRoles();
        this.cases.invalidateLawyers();
      }
    });
  }

  edit(e: Employee): void {
    this.dialog.open(EmployeeEditDialogComponent, { data: e, width: '900px', maxWidth: '96vw' }).afterClosed().subscribe((updated?: Employee) => {
      if (updated) {
        this.replace(updated);
        this.toast.success('Acesso atualizado.');
      }
    });
  }

  reset(e: Employee): void {
    this.service.resetAccess(e.uid).subscribe((r) => {
      this.clipboard.copy(r.passwordResetLink);
      this.toast.success(`Link de redefinição de ${e.displayName} copiado para a área de transferência.`);
    });
  }

  revoke(e: Employee): void {
    this.confirm('Encerrar sessões', `Todas as sessões de ${e.displayName} serão encerradas imediatamente.`, 'Encerrar', false).subscribe((ok) => {
      if (ok) this.service.revokeSessions(e.uid).subscribe((u) => { this.replace(u); this.toast.success('Sessões encerradas.'); });
    });
  }

  block(e: Employee): void {
    this.dialog
      .open(ConfirmDialogComponent, {
        data: { title: 'Bloquear acesso', message: `${e.displayName} perderá o acesso imediatamente e todas as sessões serão encerradas.`, confirmLabel: 'Bloquear', danger: true, reasonLabel: 'Motivo (registrado na auditoria)', reasonRequired: true },
        width: '480px',
      })
      .afterClosed()
      .subscribe((reason?: string) => {
        if (typeof reason === 'string' && reason) {
          this.service.block(e.uid, reason).subscribe((u) => { this.replace(u); this.toast.success('Acesso bloqueado.'); });
        }
      });
  }

  unblock(e: Employee): void {
    this.service.unblock(e.uid).subscribe((u) => { this.replace(u); this.toast.success('Acesso desbloqueado.'); });
  }

  private confirm(title: string, message: string, confirmLabel: string, danger: boolean) {
    return this.dialog.open(ConfirmDialogComponent, { data: { title, message, confirmLabel, danger }, width: '460px' }).afterClosed();
  }
}
