import { Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSelectModule } from '@angular/material/select';
import { catchError, of } from 'rxjs';
import { ApiService } from '../../core/api/api.service';
import { EmployeeOption } from '../../core/models/models';
import { ToastService } from '../../core/services/toast.service';
import { EmptyStateComponent, PageHeaderComponent } from '../../shared/components/ui';
import { saveBlob } from '../finance/finance.service';

interface ReportDef {
  key: string;
  group: string;
  title: string;
  description: string;
  usesPeriod: boolean;
  usesLawyer: boolean;
}

const ICONS: Record<string, string> = {
  Clientes: 'groups', Processos: 'gavel', 'Prazos e audiências': 'alarm', 'Contratos e honorários': 'handshake',
  Financeiro: 'account_balance_wallet', 'Equipe e comercial': 'monitoring',
};
const iso = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;

/** Central de relatórios: filtros comuns e exportação PDF/Excel com identificação do escritório. */
@Component({
  selector: 'app-reports-page',
  imports: [FormsModule, MatButtonModule, MatIconModule, MatProgressSpinnerModule, MatSelectModule, PageHeaderComponent, EmptyStateComponent],
  template: `
    <div class="page">
      <app-page-header eyebrow="Gestão" title="Relatórios" subtitle="Relatórios do escritório em PDF e Excel, com cabeçalho, período e data de geração. Cada relatório respeita as suas permissões." />

      <div class="card filters">
        <label>De <input type="date" [(ngModel)]="from" /></label>
        <label>Até <input type="date" [(ngModel)]="to" /></label>
        <div class="presets">
          <button mat-button (click)="preset(1)">Último mês</button>
          <button mat-button (click)="preset(3)">3 meses</button>
          <button mat-button (click)="preset(12)">12 meses</button>
          <button mat-button (click)="year()">Ano atual</button>
        </div>
        <mat-select class="sel" [(ngModel)]="lawyer" aria-label="Advogado" placeholder="Todos os advogados">
          <mat-option value="">Todos os advogados</mat-option>
          @for (l of lawyers(); track l.uid) { <mat-option [value]="l.uid">{{ l.displayName }}</mat-option> }
        </mat-select>
      </div>

      @if (!catalog().length && !loading()) {
        <app-empty-state icon="monitoring" title="Nenhum relatório disponível" text="Seu cargo não tem acesso a relatórios. Peça ao administrador do escritório." />
      }
      @for (g of groups(); track g.name) {
        <h3 class="group"><mat-icon>{{ icon(g.name) }}</mat-icon>{{ g.name }}</h3>
        <div class="grid">
          @for (r of g.items; track r.key) {
            <div class="card rep">
              <strong>{{ r.title }}</strong>
              <p class="muted small">{{ r.description }}</p>
              <div class="meta muted small">{{ r.usesPeriod ? 'Usa o período' : 'Posição atual' }}{{ r.usesLawyer ? ' · filtra por advogado' : '' }}</div>
              <div class="btns">
                @if (busy() === r.key) { <mat-spinner diameter="22" /> }
                @else {
                  <button mat-stroked-button (click)="generate(r, 'pdf')"><mat-icon>picture_as_pdf</mat-icon>PDF</button>
                  <button mat-stroked-button (click)="generate(r, 'xlsx')"><mat-icon>table_view</mat-icon>Excel</button>
                }
              </div>
            </div>
          }
        </div>
      }
    </div>
  `,
  styles: `
    .filters { display: flex; gap: 12px; align-items: center; flex-wrap: wrap; padding: 12px 16px; margin-bottom: 8px; }
    .filters input { margin-left: 6px; border: 1px solid var(--app-border); border-radius: 8px; padding: 7px 8px; background: var(--app-surface-2); color: var(--app-text); font: inherit; }
    .presets { display: flex; gap: 2px; flex-wrap: wrap; }
    .sel { width: 220px; border: 1px solid var(--app-border); border-radius: 10px; padding: 8px 12px; box-sizing: border-box; background: var(--app-surface); }
    .group { display: flex; align-items: center; gap: 8px; margin: 22px 0 10px; font-size: 15px; }
    .group mat-icon { color: var(--app-gold); }
    .grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(260px, 1fr)); gap: 12px; }
    .rep { padding: 14px 16px; display: flex; flex-direction: column; gap: 4px; }
    .rep p { margin: 2px 0; }
    .small { font-size: 12px; }
    .btns { display: flex; gap: 8px; margin-top: auto; padding-top: 8px; min-height: 40px; align-items: center; }
    @media (max-width: 760px) { .sel { width: 100%; } }
  `,
})
export class ReportsPageComponent {
  private readonly api = inject(ApiService);
  private readonly toast = inject(ToastService);
  readonly catalog = signal<ReportDef[]>([]);
  readonly loading = signal(true);
  readonly busy = signal<string | null>(null);
  readonly lawyers = toSignal(this.api.get<EmployeeOption[]>('/employees/lawyers', undefined, true).pipe(catchError(() => of([]))),
    { initialValue: [] as EmployeeOption[] });
  readonly groups = computed(() => {
    const map = new Map<string, ReportDef[]>();
    for (const r of this.catalog()) map.set(r.group, [...(map.get(r.group) ?? []), r]);
    return [...map.entries()].map(([name, items]) => ({ name, items }));
  });
  from = '';
  to = '';
  lawyer = '';

  constructor() {
    this.preset(12);
    this.api.get<ReportDef[]>('/reports').subscribe({ next: (c) => { this.catalog.set(c); this.loading.set(false); }, error: () => this.loading.set(false) });
  }

  icon(group: string): string {
    return ICONS[group] ?? 'description';
  }

  preset(months: number): void {
    const today = new Date();
    this.to = iso(today);
    this.from = iso(new Date(today.getFullYear(), today.getMonth() - months, today.getDate() + 1));
  }

  year(): void {
    const today = new Date();
    this.from = iso(new Date(today.getFullYear(), 0, 1));
    this.to = iso(today);
  }

  generate(r: ReportDef, format: 'pdf' | 'xlsx'): void {
    if (r.usesPeriod && (!this.from || !this.to || this.from > this.to)) {
      this.toast.error('Informe um período válido.');
      return;
    }
    this.busy.set(r.key);
    this.api.getBlob(`/reports/${r.key}`, {
      from: this.from || null, to: this.to || null, lawyerUid: r.usesLawyer ? this.lawyer || null : null, format,
    }).subscribe({
      next: (res) => { saveBlob(res, `${r.key}.${format}`); this.busy.set(null); },
      error: () => this.busy.set(null),
    });
  }
}
