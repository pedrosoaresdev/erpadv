import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ActivatedRoute } from '@angular/router';
import { ApiService } from '../../core/api/api.service';
import { AuditLog, AuditVerifyResult, CursorPage } from '../../core/models/models';
import { EmptyStateComponent, PageHeaderComponent } from '../../shared/components/ui';
import { BrDatePipe } from '../../shared/pipes/format.pipes';

const ENTITY_LABELS: Record<string, string> = {
  client: 'Cliente', case: 'Processo', user: 'Usuário', office: 'Escritório', audit: 'Auditoria',
};

/** Trilha de auditoria imutável (somente leitura) com verificação de integridade da cadeia de hash. */
@Component({
  selector: 'app-audit-log',
  imports: [ReactiveFormsModule, MatButtonModule, MatIconModule, MatFormFieldModule, MatInputModule, MatSelectModule, MatProgressBarModule, MatTooltipModule, PageHeaderComponent, EmptyStateComponent, BrDatePipe],
  template: `
    <div class="page">
      <app-page-header eyebrow="Segurança e LGPD" title="Auditoria" subtitle="Registro imutável de quem acessou, alterou ou excluiu informações.">
        <button mat-stroked-button (click)="verify()" [disabled]="verifying()"><mat-icon>verified</mat-icon>{{ verifying() ? 'Verificando…' : 'Verificar integridade' }}</button>
      </app-page-header>

      @if (verifyResult(); as v) {
        <div class="verify card" [class.ok]="v.valid" [class.bad]="!v.valid">
          <mat-icon>{{ v.valid ? 'verified_user' : 'gpp_bad' }}</mat-icon>
          <div>
            <strong>{{ v.valid ? 'Cadeia íntegra' : 'Inconsistência detectada' }}</strong>
            <div>{{ v.message }} — {{ v.checked }} registros verificados{{ v.brokenAtSeq ? ' · registro nº ' + v.brokenAtSeq : '' }}.</div>
          </div>
        </div>
      }

      <div class="card">
        <form class="filters" [formGroup]="filters" (ngSubmit)="load(false)">
          <mat-form-field appearance="outline" subscriptSizing="dynamic">
            <mat-label>Entidade</mat-label>
            <mat-select formControlName="entityType" (selectionChange)="load(false)">
              <mat-option value="">Todas</mat-option>
              @for (e of entityOptions; track e.value) { <mat-option [value]="e.value">{{ e.label }}</mat-option> }
            </mat-select>
          </mat-form-field>
          <mat-form-field appearance="outline" subscriptSizing="dynamic" class="grow">
            <mat-label>ID do usuário (ator)</mat-label>
            <input matInput formControlName="actorUid" />
          </mat-form-field>
          <mat-form-field appearance="outline" subscriptSizing="dynamic" class="grow">
            <mat-label>ID do registro</mat-label>
            <input matInput formControlName="entityId" />
          </mat-form-field>
          <button mat-flat-button type="submit"><mat-icon>filter_alt</mat-icon>Filtrar</button>
        </form>
        @if (loading()) { <mat-progress-bar mode="indeterminate" /> }
        @if (!loading() && items().length === 0) {
          <app-empty-state icon="history" title="Nenhum registro encontrado" />
        } @else {
          <div class="table-wrap">
            <table class="data">
              <thead><tr><th>#</th><th>Data</th><th>Ação</th><th>Descrição</th><th>Usuário</th><th>IP</th><th>Hash</th></tr></thead>
              <tbody>
                @for (a of items(); track a.id) {
                  <tr>
                    <td class="mono muted">{{ a.seq }}</td>
                    <td class="nowrap">{{ a.at | brDate: true }}</td>
                    <td><span class="badge">{{ a.action }}</span></td>
                    <td>{{ a.summary }}<div class="muted small">{{ entityLabel(a.entityType) }}{{ a.entityId ? ' · ' + a.entityId : '' }}</div></td>
                    <td>{{ a.actorEmail || a.actorUid }}</td>
                    <td class="mono muted">{{ a.ip || '—' }}</td>
                    <td class="mono muted" [matTooltip]="a.hash">{{ a.hash.slice(0, 10) }}…</td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
          @if (cursor()) { <div class="more"><button mat-stroked-button (click)="load(true)">Carregar mais</button></div> }
        }
      </div>
    </div>
  `,
  styles: `
    .filters { display: flex; flex-wrap: wrap; gap: 12px; padding: 16px; align-items: center; border-bottom: 1px solid var(--app-border); }
    .filters mat-form-field { width: 200px; }
    .filters .grow { flex: 1 1 220px; }
    .verify { display: flex; gap: 12px; align-items: center; padding: 14px 18px; margin-bottom: 16px; }
    .verify.ok { background: var(--app-success-bg); color: var(--app-success); border-color: transparent; }
    .verify.bad { background: var(--app-danger-bg); color: var(--app-danger); border-color: transparent; }
    .small { font-size: 12px; }
    .nowrap { white-space: nowrap; }
    .more { display: flex; justify-content: center; padding: 16px; }
  `,
})
export class AuditLogComponent {
  private readonly api = inject(ApiService);
  readonly entityOptions = Object.entries(ENTITY_LABELS).map(([value, label]) => ({ value, label }));
  readonly items = signal<AuditLog[]>([]);
  readonly cursor = signal<string | null>(null);
  readonly loading = signal(false);
  readonly verifying = signal(false);
  readonly verifyResult = signal<AuditVerifyResult | null>(null);
  readonly filters = inject(FormBuilder).nonNullable.group({
    entityType: [''],
    actorUid: [inject(ActivatedRoute).snapshot.queryParamMap.get('ator') ?? ''],
    entityId: [''],
  });

  constructor() {
    this.load(false);
  }

  entityLabel(type: string): string {
    return ENTITY_LABELS[type] ?? type;
  }

  load(more: boolean): void {
    this.loading.set(true);
    const f = this.filters.getRawValue();
    this.api
      .get<CursorPage<AuditLog>>('/audit', { ...f, actorUid: f.actorUid.trim(), entityId: f.entityId.trim(), cursor: more ? this.cursor() : null, size: 50 })
      .subscribe({
        next: (page) => {
          this.items.set(more ? [...this.items(), ...page.items] : page.items);
          this.cursor.set(page.nextCursor);
          this.loading.set(false);
        },
        error: () => this.loading.set(false),
      });
  }

  verify(): void {
    this.verifying.set(true);
    this.api.get<AuditVerifyResult>('/audit/verify').subscribe({
      next: (r) => {
        this.verifyResult.set(r);
        this.verifying.set(false);
        this.load(false);
      },
      error: () => this.verifying.set(false),
    });
  }
}
