import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { AuthService } from '../../core/auth/auth.service';
import { CourtCalendar } from '../../core/models/models';
import { ToastService } from '../../core/services/toast.service';
import { BrDatePipe } from '../../shared/pipes/format.pipes';
import { DeadlinesService } from './deadlines.service';

/** Feriados nacionais (automáticos) + feriados locais e suspensões cadastrados pelo escritório. */
@Component({
  selector: 'app-court-calendar',
  imports: [ReactiveFormsModule, MatButtonModule, MatFormFieldModule, MatIconModule, MatInputModule, MatSelectModule, MatTooltipModule, BrDatePipe],
  template: `
    <div class="layout">
      <section class="card card-pad">
        <div class="head">
          <div class="card-title"><mat-icon>event_busy</mat-icon>Calendário {{ year() }}</div>
          <div class="nav">
            <button mat-icon-button (click)="setYear(year() - 1)" aria-label="Ano anterior"><mat-icon>chevron_left</mat-icon></button>
            <button mat-icon-button (click)="setYear(year() + 1)" aria-label="Próximo ano"><mat-icon>chevron_right</mat-icon></button>
          </div>
        </div>
        <p class="muted small">Recesso forense de 20/12 a 20/01 é aplicado automaticamente nas contagens em dias úteis (CPC, art. 220).</p>
        <h4>Cadastrados pelo escritório</h4>
        @if (data()?.office?.length) {
          <ul class="list">
            @for (e of data()!.office; track e.id) {
              <li>
                <span class="badge" [class.warning]="e.kind === 'SUSPENSAO'" [class.gold]="e.kind === 'FERIADO'">{{ e.kind === 'FERIADO' ? 'Feriado' : 'Suspensão' }}</span>
                <span class="mono">{{ e.startDate | brDate }}{{ e.endDate !== e.startDate ? ' a ' + (e.endDate | brDate) : '' }}</span>
                <span class="desc">{{ e.description }}</span>
                <span class="muted">{{ e.court || 'Todos os tribunais' }}</span>
                @if (auth.has('DEADLINES_WRITE')) {
                  <button mat-icon-button (click)="remove(e.id)" matTooltip="Remover" aria-label="Remover"><mat-icon>delete</mat-icon></button>
                }
              </li>
            }
          </ul>
        } @else {
          <p class="muted">Nenhum feriado local ou suspensão cadastrado neste ano.</p>
        }
        <h4>Feriados nacionais e forenses</h4>
        <ul class="list">
          @for (h of data()?.national ?? []; track h.date) {
            <li><span class="badge">Nacional</span><span class="mono">{{ h.date | brDate }}</span><span class="desc">{{ h.description }}</span></li>
          }
        </ul>
      </section>

      @if (auth.has('DEADLINES_WRITE')) {
        <section class="card card-pad">
          <div class="card-title"><mat-icon>add_circle</mat-icon>Adicionar feriado ou suspensão</div>
          <form [formGroup]="form" (ngSubmit)="add()" class="form-grid">
            <mat-form-field class="c6"><mat-label>Início</mat-label><input matInput type="date" formControlName="startDate" /></mat-form-field>
            <mat-form-field class="c6"><mat-label>Fim (opcional)</mat-label><input matInput type="date" formControlName="endDate" /></mat-form-field>
            <mat-form-field class="c6">
              <mat-label>Tipo</mat-label>
              <mat-select formControlName="kind">
                <mat-option value="FERIADO">Feriado</mat-option>
                <mat-option value="SUSPENSAO">Suspensão de prazos</mat-option>
              </mat-select>
            </mat-form-field>
            <mat-form-field class="c6"><mat-label>Tribunal (vazio = todos)</mat-label><input matInput formControlName="court" placeholder="TJSP" /></mat-form-field>
            <mat-form-field>
              <mat-label>Descrição / ato normativo</mat-label>
              <input matInput formControlName="description" maxlength="200" placeholder="Ex.: Aniversário da cidade · Provimento CSM nº …" />
              @if (form.controls.description.invalid) { <mat-error>Obrigatório</mat-error> }
            </mat-form-field>
            <div class="actions"><button mat-flat-button type="submit" [disabled]="saving()"><mat-icon>add</mat-icon>Adicionar</button></div>
          </form>
        </section>
      }
    </div>
  `,
  styles: `
    .layout { display: grid; grid-template-columns: 1.4fr 1fr; gap: 16px; margin-top: 16px; align-items: start; }
    @media (max-width: 1000px) { .layout { grid-template-columns: 1fr; } }
    .head { display: flex; justify-content: space-between; align-items: center; }
    .small { font-size: 12px; }
    h4 { font-size: 12px; text-transform: uppercase; letter-spacing: .6px; color: var(--app-text-muted); margin: 18px 0 8px; }
    .list { list-style: none; margin: 0; padding: 0; }
    .list li { display: flex; gap: 10px; align-items: center; padding: 8px 0; border-bottom: 1px solid var(--app-border); flex-wrap: wrap; }
    .desc { flex: 1; font-weight: 500; }
    .actions { grid-column: span 12; display: flex; justify-content: flex-end; }
  `,
})
export class CourtCalendarComponent {
  readonly auth = inject(AuthService);
  private readonly service = inject(DeadlinesService);
  private readonly toast = inject(ToastService);
  readonly year = signal(new Date().getFullYear());
  readonly data = signal<CourtCalendar | null>(null);
  readonly saving = signal(false);
  readonly form = inject(FormBuilder).nonNullable.group({
    startDate: ['', Validators.required],
    endDate: [''],
    kind: ['FERIADO' as 'FERIADO' | 'SUSPENSAO', Validators.required],
    court: [''],
    description: ['', [Validators.required, Validators.maxLength(200)]],
  });

  constructor() {
    this.load();
  }

  setYear(y: number): void {
    this.year.set(y);
    this.load();
  }

  load(): void {
    this.service.courtCalendar(this.year()).subscribe((d) => this.data.set(d));
  }

  add(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    this.saving.set(true);
    this.service
      .addCourtCalendar({ startDate: v.startDate, endDate: v.endDate || v.startDate, kind: v.kind, court: v.court.trim() || null, description: v.description.trim() })
      .subscribe({
        next: (e) => {
          this.saving.set(false);
          this.toast.success('Registro adicionado. Novas contagens já o consideram.');
          this.form.reset({ kind: 'FERIADO' });
          this.setYear(Number(e.startDate.slice(0, 4)));
        },
        error: () => this.saving.set(false),
      });
  }

  remove(id: string): void {
    this.service.deleteCourtCalendar(id).subscribe(() => {
      this.toast.success('Registro removido.');
      this.load();
    });
  }
}
