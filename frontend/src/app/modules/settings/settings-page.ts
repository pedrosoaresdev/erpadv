import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatMenuModule } from '@angular/material/menu';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatTabsModule } from '@angular/material/tabs';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';
import { Address } from '../../core/models/models';
import { ToastService } from '../../core/services/toast.service';
import { ConfirmDialogComponent } from '../../shared/components/confirm-dialog';
import { EmptyStateComponent, PageHeaderComponent, StatCardComponent } from '../../shared/components/ui';
import { UF } from '../../shared/labels';
import { BrDatePipe } from '../../shared/pipes/format.pipes';
import { ModuleView, OfficeService, OfficeSettings, OfficeUsage, SUBSCRIPTION_LABELS, Unit, formatBytes, formatMb } from './office.service';
import { UnitDialogComponent } from './unit-dialog';

const TABS = ['dados', 'modulos', 'unidades', 'uso'];

/** Configurações do escritório (especificação, seção 18): dados, logo, módulos, unidades e uso do sistema. */
@Component({
  selector: 'app-settings-page',
  imports: [FormsModule, RouterLink, MatButtonModule, MatFormFieldModule, MatIconModule, MatInputModule, MatMenuModule, MatSelectModule,
    MatSlideToggleModule, MatTabsModule, PageHeaderComponent, EmptyStateComponent, StatCardComponent, BrDatePipe],
  template: `
    <div class="page">
      <app-page-header eyebrow="Administração" title="Configurações do escritório"
        subtitle="Dados cadastrais, logo, módulos em uso, unidades e acompanhamento do uso do sistema." />

      <mat-tab-group animationDuration="120ms" mat-stretch-tabs="false" [selectedIndex]="tab()" (selectedIndexChange)="onTab($event)">
        <!-- ------------------------------------------------------------ dados -->
        <mat-tab label="Dados do escritório">
          @if (form; as f) {
            <div class="two">
              <section class="card pad">
                <h3>Identificação</h3>
                <div class="grid">
                  <mat-form-field class="c2"><mat-label>Nome do escritório</mat-label><input matInput [(ngModel)]="f.name" maxlength="160" required /></mat-form-field>
                  <mat-form-field><mat-label>CNPJ</mat-label><input matInput [(ngModel)]="f.cnpj" maxlength="18" placeholder="00.000.000/0000-00" /></mat-form-field>
                  <mat-form-field><mat-label>Registro da sociedade na OAB</mat-label><input matInput [(ngModel)]="f.oabRegistration" maxlength="40" /></mat-form-field>
                  <mat-form-field><mat-label>Telefone</mat-label><input matInput [(ngModel)]="f.phone" maxlength="30" /></mat-form-field>
                  <mat-form-field><mat-label>E-mail</mat-label><input matInput type="email" [(ngModel)]="f.email" maxlength="160" /></mat-form-field>
                  <mat-form-field class="c2"><mat-label>Site</mat-label><input matInput [(ngModel)]="f.website" maxlength="200" placeholder="https://" /></mat-form-field>
                </div>
                <h3>Endereço</h3>
                <div class="grid">
                  <mat-form-field><mat-label>CEP</mat-label><input matInput [(ngModel)]="address.zipCode" maxlength="9" /></mat-form-field>
                  <mat-form-field class="c2"><mat-label>Logradouro</mat-label><input matInput [(ngModel)]="address.street" maxlength="160" /></mat-form-field>
                  <mat-form-field><mat-label>Número</mat-label><input matInput [(ngModel)]="address.number" maxlength="20" /></mat-form-field>
                  <mat-form-field><mat-label>Complemento</mat-label><input matInput [(ngModel)]="address.complement" maxlength="80" /></mat-form-field>
                  <mat-form-field><mat-label>Bairro</mat-label><input matInput [(ngModel)]="address.neighborhood" maxlength="80" /></mat-form-field>
                  <mat-form-field><mat-label>Cidade</mat-label><input matInput [(ngModel)]="address.city" maxlength="80" /></mat-form-field>
                  <mat-form-field><mat-label>UF</mat-label>
                    <mat-select [(ngModel)]="address.state"><mat-option [value]="null">—</mat-option>@for (uf of ufs; track uf) { <mat-option [value]="uf">{{ uf }}</mat-option> }</mat-select>
                  </mat-form-field>
                </div>
                <div class="bar">
                  <span class="spacer"></span>
                  <button mat-flat-button (click)="saveData()" [disabled]="saving() || !f.name.trim()"><mat-icon>save</mat-icon>Salvar dados</button>
                </div>
              </section>

              <section class="card pad logo-card">
                <h3>Logo</h3>
                <div class="logo-box">
                  @if (office.logoUrl(); as url) { <img [src]="url" alt="Logo do escritório" /> }
                  @else { <mat-icon>image</mat-icon><span class="muted small">Sem logo</span> }
                </div>
                <p class="muted small">PNG ou JPEG de até 1 MB. Aparece no menu lateral do sistema.</p>
                <input #file type="file" accept="image/png,image/jpeg" hidden (change)="uploadLogo(file)" />
                <div class="bar">
                  <button mat-stroked-button (click)="file.click()" [disabled]="saving()"><mat-icon>upload</mat-icon>{{ settings()?.hasLogo ? 'Trocar logo' : 'Enviar logo' }}</button>
                  @if (settings()?.hasLogo) { <button mat-button (click)="removeLogo()" [disabled]="saving()"><mat-icon>delete</mat-icon>Remover</button> }
                </div>
                <h3>Papel timbrado</h3>
                <p class="muted small">O cabeçalho e o rodapé dos documentos são configurados no Editor Jurídico.</p>
                <a mat-button routerLink="/editor-juridico"><mat-icon>edit_document</mat-icon>Abrir Editor Jurídico</a>
                @if (settings(); as s) {
                  <h3>Plano</h3>
                  <p class="small">{{ s.planName || s.planCode || '—' }}
                    @if (s.subscriptionStatus) { <span class="badge">{{ subLabels[s.subscriptionStatus] }}</span> }</p>
                  <a mat-button routerLink="/plano"><mat-icon>workspace_premium</mat-icon>Plano e Assinatura</a>
                }
              </section>
            </div>
          }
        </mat-tab>

        <!-- ------------------------------------------------------------ módulos -->
        <mat-tab label="Módulos">
          <section class="card pad">
            <p class="muted">Escolha os módulos que o escritório usa. Módulos desligados somem do menu e ficam bloqueados para toda a equipe
              (os dados são preservados e voltam ao religar). Módulos fora do plano podem ser contratados em <a routerLink="/plano">Plano e Assinatura</a>.</p>
            <div class="modules">
              @for (m of modules; track m.code) {
                <div class="module" [class.off]="!m.inPlan">
                  <div>
                    <strong>{{ m.label }}</strong>
                    <div class="muted small">{{ m.inPlan ? (m.enabled ? 'Em uso' : 'Desligado pelo escritório') : 'Não incluído no plano atual' }}</div>
                  </div>
                  <mat-slide-toggle [(ngModel)]="m.enabled" [disabled]="!m.inPlan" [attr.aria-label]="'Usar ' + m.label" />
                </div>
              }
            </div>
            <p class="muted small">Clientes, processos, prazos, agenda, documentos, editor, recibos, equipe, auditoria e segurança fazem parte de todos os planos.</p>
            <div class="bar">
              <span class="spacer"></span>
              <button mat-flat-button (click)="saveModules()" [disabled]="saving()"><mat-icon>save</mat-icon>Salvar módulos</button>
            </div>
          </section>
        </mat-tab>

        <!-- ------------------------------------------------------------ unidades -->
        <mat-tab label="Unidades e filiais">
          <section class="card">
            <div class="head">
              <p class="muted">Cadastre as unidades (filiais) do escritório, defina quem trabalha em cada uma e vincule os processos. O dashboard
                e a lista de processos podem ser filtrados por unidade.</p>
              <button mat-flat-button (click)="editUnit(null)"><mat-icon>add_business</mat-icon>Nova unidade</button>
            </div>
            @if (units().length === 0) {
              <app-empty-state icon="store" title="Nenhuma unidade cadastrada" text="Escritórios com uma só sede não precisam cadastrar unidades." />
            } @else {
              <div class="table-wrap">
                <table class="data">
                  <thead><tr><th>Unidade</th><th>Local</th><th>Responsável</th><th>Integrantes</th><th>Processos</th><th>Situação</th><th></th></tr></thead>
                  <tbody>
                    @for (u of units(); track u.id) {
                      <tr>
                        <td><strong>{{ u.name }}</strong><div class="muted small">{{ u.cnpj || u.email || '' }}</div></td>
                        <td class="muted">{{ u.address?.city ? u.address?.city + (u.address?.state ? '/' + u.address?.state : '') : '—' }}</td>
                        <td>{{ u.managerName || '—' }}</td>
                        <td class="mono">{{ u.members }}</td>
                        <td class="mono">{{ u.cases }}</td>
                        <td>@if (u.active) { <span class="badge success">Ativa</span> } @else { <span class="badge">Inativa</span> }</td>
                        <td class="actions">
                          <button mat-icon-button [matMenuTriggerFor]="menu" aria-label="Ações da unidade"><mat-icon>more_vert</mat-icon></button>
                          <mat-menu #menu="matMenu" xPosition="before">
                            <button mat-menu-item (click)="editUnit(u)"><mat-icon>edit</mat-icon>Editar e integrantes</button>
                            <button mat-menu-item (click)="filterCases(u)"><mat-icon>gavel</mat-icon>Ver processos</button>
                            <button mat-menu-item class="danger" (click)="deleteUnit(u)"><mat-icon>delete</mat-icon>Excluir</button>
                          </mat-menu>
                        </td>
                      </tr>
                    }
                  </tbody>
                </table>
              </div>
            }
          </section>
        </mat-tab>

        <!-- ------------------------------------------------------------ uso -->
        <mat-tab label="Uso do sistema">
          @if (usage(); as u) {
            <div class="stats">
              <app-stat-card label="Usuários" [value]="u.users + (u.maxUsers ? ' / ' + u.maxUsers : '')" icon="group" [accent]="true"
                [hint]="u.maxUsers ? pct(u.users, u.maxUsers) + '% do plano' : 'sem limite no plano'" [tone]="u.maxUsers && u.users >= u.maxUsers ? 'warning' : ''" />
              <app-stat-card label="Armazenamento" [value]="bytes(u.storageBytes)" icon="cloud"
                [hint]="'de ' + mb(u.maxStorageMb) + (u.maxStorageMb ? ' · ' + pct(u.storageBytes, u.maxStorageMb * 1048576) + '%' : '')"
                [tone]="u.maxStorageMb && u.storageBytes >= u.maxStorageMb * 1048576 * 0.9 ? 'warning' : ''" />
              <app-stat-card label="Clientes" [value]="u.clients" icon="groups" />
              <app-stat-card label="Processos" [value]="u.cases" icon="gavel" />
              <app-stat-card label="Documentos" [value]="u.documents" icon="folder_open" />
            </div>
            <section class="card">
              <div class="head"><h3>Atividade recente</h3><a mat-button routerLink="/auditoria"><mat-icon>verified_user</mat-icon>Auditoria completa</a></div>
              @if (u.recentActivity.length === 0) {
                <app-empty-state icon="history" title="Sem atividade registrada" />
              } @else {
                <ul class="activity">
                  @for (a of u.recentActivity; track a.id) {
                    <li><span class="muted mono small">{{ a.at | brDate: true }}</span><span>{{ a.summary || a.action }}</span><span class="muted small">{{ a.actorName || a.actorEmail || 'Sistema' }}</span></li>
                  }
                </ul>
              }
            </section>
          }
        </mat-tab>
      </mat-tab-group>
    </div>
  `,
  styles: `
    .pad { padding: 18px 20px; }
    .two { display: grid; grid-template-columns: minmax(0, 2fr) minmax(260px, 1fr); gap: 16px; margin-top: 16px; align-items: start; }
    .grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 0 12px; }
    .c2 { grid-column: span 2; }
    h3 { margin: 8px 0 10px; font-size: 15px; }
    .bar { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
    .small { font-size: 12px; }
    .logo-box { height: 120px; border: 1px dashed var(--app-border); border-radius: 12px; display: flex; flex-direction: column; gap: 6px;
      align-items: center; justify-content: center; background: var(--app-surface-2); margin-bottom: 8px; }
    .logo-box img { max-height: 100px; max-width: 90%; object-fit: contain; }
    .card { margin-top: 16px; }
    .two .card { margin-top: 0; }
    .modules { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 12px; margin: 12px 0; }
    .module { display: flex; justify-content: space-between; align-items: center; gap: 12px; padding: 12px 14px; border: 1px solid var(--app-border); border-radius: 12px; }
    .module.off { opacity: .6; }
    .head { display: flex; gap: 12px; align-items: center; justify-content: space-between; padding: 14px 16px; border-bottom: 1px solid var(--app-border); flex-wrap: wrap; }
    .head p { margin: 0; flex: 1 1 320px; }
    .head h3 { margin: 0; }
    .actions { text-align: right; width: 56px; }
    .danger { color: var(--app-danger); }
    .stats { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 16px; margin-top: 16px; }
    .activity { list-style: none; margin: 0; padding: 0; }
    .activity li { display: grid; grid-template-columns: 140px minmax(0, 1fr) 200px; gap: 12px; padding: 10px 16px; border-bottom: 1px solid var(--app-border); align-items: center; }
    @media (max-width: 900px) {
      .two, .grid { grid-template-columns: minmax(0, 1fr); }
      .c2 { grid-column: auto; }
      .activity li { grid-template-columns: minmax(0, 1fr); gap: 2px; }
    }
  `,
})
export class SettingsPageComponent {
  readonly office = inject(OfficeService);
  private readonly auth = inject(AuthService);
  private readonly toast = inject(ToastService);
  private readonly dialog = inject(MatDialog);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  readonly ufs = UF;
  readonly subLabels = SUBSCRIPTION_LABELS as Record<string, string>;
  readonly tab = signal(Math.max(0, TABS.indexOf(this.route.snapshot.queryParamMap.get('aba') ?? '')));
  readonly settings = signal<OfficeSettings | null>(null);
  readonly units = signal<Unit[]>([]);
  readonly usage = signal<OfficeUsage | null>(null);
  readonly saving = signal(false);
  readonly canManage = computed(() => this.auth.has('SETTINGS_MANAGE'));
  form: { name: string; cnpj: string | null; email: string | null; phone: string | null; website: string | null; oabRegistration: string | null } | null = null;
  address: Address = {};
  modules: ModuleView[] = [];

  constructor() {
    this.load();
    this.loadUnits();
    this.office.usage().subscribe((u) => this.usage.set(u));
  }

  private load(): void {
    this.office.settings().subscribe((s) => this.apply(s));
  }

  private apply(s: OfficeSettings): void {
    this.settings.set(s);
    this.form = { name: s.name, cnpj: s.cnpj, email: s.email, phone: s.phone, website: s.website, oabRegistration: s.oabRegistration };
    this.address = { ...(s.address ?? {}) };
    this.modules = s.modules.map((m) => ({ ...m }));
  }

  loadUnits(): void {
    this.office.units().subscribe((u) => this.units.set(u));
  }

  onTab(i: number): void {
    this.tab.set(i);
    void this.router.navigate([], { queryParams: { aba: TABS[i] }, replaceUrl: true });
  }

  bytes = formatBytes;
  mb = formatMb;
  pct(v: number, max: number): number {
    return max ? Math.min(100, Math.round((v / max) * 100)) : 0;
  }

  saveData(): void {
    if (!this.form) return;
    const blank = (v: string | null | undefined) => (v && v.trim() ? v.trim() : null);
    const hasAddress = Object.values(this.address).some((v) => v && String(v).trim());
    this.saving.set(true);
    this.office.update({
      name: this.form.name.trim(), cnpj: blank(this.form.cnpj), email: blank(this.form.email), phone: blank(this.form.phone),
      website: blank(this.form.website), oabRegistration: blank(this.form.oabRegistration), address: hasAddress ? this.address : null,
    }).subscribe({
      next: (s) => { this.apply(s); this.saving.set(false); this.toast.success('Dados do escritório salvos.'); void this.auth.refresh(); },
      error: () => this.saving.set(false),
    });
  }

  uploadLogo(input: HTMLInputElement): void {
    const file = input.files?.[0];
    input.value = '';
    if (!file) return;
    if (file.size > 1024 * 1024) { this.toast.error('A logo deve ter no máximo 1 MB.'); return; }
    this.saving.set(true);
    this.office.uploadLogo(file).subscribe({
      next: (s) => { this.settings.set(s); this.saving.set(false); this.toast.success('Logo atualizada.'); },
      error: () => this.saving.set(false),
    });
  }

  removeLogo(): void {
    this.saving.set(true);
    this.office.deleteLogo().subscribe({
      next: (s) => { this.settings.set(s); this.saving.set(false); this.toast.success('Logo removida.'); },
      error: () => this.saving.set(false),
    });
  }

  saveModules(): void {
    this.saving.set(true);
    this.office.setModules(this.modules.filter((m) => m.inPlan && m.enabled).map((m) => m.code)).subscribe({
      next: (s) => {
        this.apply(s);
        this.saving.set(false);
        this.toast.success('Módulos atualizados. O menu reflete a mudança no próximo carregamento.');
        void this.auth.refresh();
      },
      error: () => this.saving.set(false),
    });
  }

  editUnit(u: Unit | null): void {
    this.dialog.open(UnitDialogComponent, { data: u, width: '760px', maxWidth: '96vw' }).afterClosed().subscribe((r?: Unit) => {
      if (r) { this.toast.success('Unidade salva.'); this.loadUnits(); }
    });
  }

  filterCases(u: Unit): void {
    void this.router.navigate(['/processos'], { queryParams: { unidade: u.id } });
  }

  deleteUnit(u: Unit): void {
    this.dialog.open(ConfirmDialogComponent, {
      data: { title: 'Excluir unidade', message: `Excluir a unidade ${u.name}? Só é possível quando não há integrantes nem processos vinculados.`,
        confirmLabel: 'Excluir', danger: true },
      width: '460px',
    }).afterClosed().subscribe((ok) => {
      if (ok) this.office.deleteUnit(u.id).subscribe(() => { this.toast.success('Unidade excluída.'); this.loadUnits(); });
    });
  }
}
