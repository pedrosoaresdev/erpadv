import { Component, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Router } from '@angular/router';
import { debounceTime } from 'rxjs';
import { AuthService } from '../../core/auth/auth.service';
import { CaseStatus, LegalArea, LegalCase } from '../../core/models/models';
import { ToastService } from '../../core/services/toast.service';
import { EmptyStateComponent, PageHeaderComponent } from '../../shared/components/ui';
import { AREA_LABELS, CASE_STATUS_LABELS, CASE_STATUS_TONE, enumOptions } from '../../shared/labels';
import { CentsPipe } from '../../shared/pipes/format.pipes';
import { CaseFormDialogComponent } from './case-form-dialog';
import { CasesService } from './cases.service';

@Component({
  selector: 'app-cases-list',
  imports: [
    ReactiveFormsModule, MatButtonModule, MatIconModule, MatFormFieldModule, MatInputModule, MatSelectModule,
    MatCheckboxModule, MatProgressBarModule, MatTooltipModule, PageHeaderComponent, EmptyStateComponent, CentsPipe,
  ],
  template: `
    <div class="page">
      <app-page-header eyebrow="Contencioso" title="Processos" subtitle="Acompanhamento processual do escritório.">
        @if (auth.has('CASES_WRITE')) {
          <button mat-flat-button (click)="create()"><mat-icon>add</mat-icon>Novo processo</button>
        }
      </app-page-header>

      <div class="card">
        <form class="filters" [formGroup]="filters">
          <mat-form-field appearance="outline" subscriptSizing="dynamic" class="grow">
            <mat-icon matPrefix>search</mat-icon>
            <mat-label>Nº CNJ, cliente, parte contrária ou assunto</mat-label>
            <input matInput formControlName="search" />
          </mat-form-field>
          <mat-form-field appearance="outline" subscriptSizing="dynamic">
            <mat-label>Situação</mat-label>
            <mat-select formControlName="status">
              <mat-option value="">Todas</mat-option>
              @for (o of statuses; track o.value) { <mat-option [value]="o.value">{{ o.label }}</mat-option> }
            </mat-select>
          </mat-form-field>
          <mat-form-field appearance="outline" subscriptSizing="dynamic">
            <mat-label>Área</mat-label>
            <mat-select formControlName="area">
              <mat-option value="">Todas</mat-option>
              @for (o of areas; track o.value) {
                @if (o.value !== 'CRIMINAL' || auth.has('CRIMINAL_READ')) { <mat-option [value]="o.value">{{ o.label }}</mat-option> }
              }
            </mat-select>
          </mat-form-field>
          <mat-form-field appearance="outline" subscriptSizing="dynamic">
            <mat-label>Advogado</mat-label>
            <mat-select formControlName="responsibleUid">
              <mat-option value="">Todos</mat-option>
              @for (l of lawyers(); track l.uid) { <mat-option [value]="l.uid">{{ l.displayName }}</mat-option> }
            </mat-select>
          </mat-form-field>
          <mat-form-field appearance="outline" subscriptSizing="dynamic" class="small">
            <mat-label>Tribunal</mat-label>
            <input matInput formControlName="court" placeholder="TJSP" />
          </mat-form-field>
          <mat-checkbox formControlName="includeArchived">Incluir arquivados</mat-checkbox>
        </form>
        @if (loading()) { <mat-progress-bar mode="indeterminate" /> }

        @if (!loading() && items().length === 0) {
          <app-empty-state icon="gavel" title="Nenhum processo encontrado" text="Ajuste os filtros ou cadastre um novo processo." />
        } @else {
          <div class="table-wrap">
            <table class="data">
              <thead>
                <tr><th>Processo</th><th>Cliente</th><th>Juízo</th><th>Área</th><th>Responsável</th><th>Valor da causa</th><th>Situação</th></tr>
              </thead>
              <tbody>
                @for (p of items(); track p.id) {
                  <tr class="clickable" (click)="open(p)" tabindex="0" (keydown.enter)="open(p)">
                    <td>
                      <div class="mono strong">{{ p.cnjNumber || 'Não distribuído' }}</div>
                      <div class="muted small">{{ p.subject || p.proceduralClass || '—' }}</div>
                    </td>
                    <td><div>{{ p.clientName }}</div><div class="muted small">x {{ p.opposingParty || '—' }}</div></td>
                    <td><div>{{ p.court || '—' }}</div><div class="muted small">{{ p.courtDivision }}{{ p.district ? ' · ' + p.district : '' }}</div></td>
                    <td>
                      {{ p.areaLabel }}
                      @if (p.restricted) { <mat-icon class="lock" matTooltip="Acesso restrito">lock</mat-icon> }
                    </td>
                    <td>{{ p.responsibleName }}</td>
                    <td class="mono">{{ p.claimValueCents | cents }}</td>
                    <td><span class="badge" [class]="tone[p.status]">{{ p.statusLabel }}</span></td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
          @if (nextCursor()) {
            <div class="more"><button mat-stroked-button (click)="fetch(nextCursor())" [disabled]="loading()">Carregar mais</button></div>
          }
        }
      </div>
    </div>
  `,
  styles: `
    .filters { display: flex; flex-wrap: wrap; gap: 12px; padding: 16px; border-bottom: 1px solid var(--app-border); align-items: center; }
    .filters mat-form-field { width: 190px; }
    .filters mat-form-field.small { width: 120px; }
    .filters .grow { flex: 1 1 300px; width: auto; }
    .strong { font-weight: 600; }
    .small { font-size: 12px; }
    .lock { font-size: 16px; width: 16px; height: 16px; vertical-align: middle; color: var(--app-gold); }
    .more { display: flex; justify-content: center; padding: 16px; border-top: 1px solid var(--app-border); }
  `,
})
export class CasesListComponent {
  readonly auth = inject(AuthService);
  private readonly service = inject(CasesService);
  private readonly dialog = inject(MatDialog);
  private readonly router = inject(Router);
  private readonly toast = inject(ToastService);

  readonly areas = enumOptions(AREA_LABELS);
  readonly statuses = enumOptions(CASE_STATUS_LABELS);
  readonly tone = CASE_STATUS_TONE;
  readonly lawyers = toSignal(this.service.lawyers(), { initialValue: [] });
  readonly items = signal<LegalCase[]>([]);
  readonly nextCursor = signal<string | null>(null);
  readonly loading = signal(false);

  readonly filters = inject(FormBuilder).nonNullable.group({
    search: [''],
    status: ['' as CaseStatus | ''],
    area: ['' as LegalArea | ''],
    responsibleUid: [''],
    court: [''],
    includeArchived: [false],
  });

  constructor() {
    this.filters.valueChanges.pipe(debounceTime(350), takeUntilDestroyed()).subscribe(() => this.fetch(null));
    this.fetch(null);
  }

  fetch(cursor: string | null): void {
    this.loading.set(true);
    const f = this.filters.getRawValue();
    this.service
      .list({ ...f, search: f.search.trim(), court: f.court.trim().toUpperCase() }, cursor)
      .subscribe({
        next: (page) => {
          this.items.set(cursor ? [...this.items(), ...page.items] : page.items);
          this.nextCursor.set(page.nextCursor);
          this.loading.set(false);
        },
        error: () => this.loading.set(false),
      });
  }

  open(p: LegalCase): void {
    void this.router.navigate(['/processos', p.id]);
  }

  create(): void {
    this.dialog
      .open(CaseFormDialogComponent, { data: {}, width: '900px', maxWidth: '96vw' })
      .afterClosed()
      .subscribe((created?: LegalCase) => {
        if (created) {
          this.toast.success('Processo cadastrado.');
          void this.router.navigate(['/processos', created.id]);
        }
      });
  }
}
