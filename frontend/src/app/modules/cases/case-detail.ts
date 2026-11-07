import { Component, effect, inject, input, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Router, RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';
import { CaseMovement, LegalCase } from '../../core/models/models';
import { ToastService } from '../../core/services/toast.service';
import { ConfirmDialogComponent } from '../../shared/components/confirm-dialog';
import { EmptyStateComponent, LoadingComponent } from '../../shared/components/ui';
import { CASE_STATUS_TONE, MOVEMENT_ICONS, MOVEMENT_LABELS } from '../../shared/labels';
import { BrDatePipe, CentsPipe } from '../../shared/pipes/format.pipes';
import { CaseFormDialogComponent } from './case-form-dialog';
import { CasesService } from './cases.service';
import { MovementDialogComponent } from './movement-dialog';
import { Deadline } from '../../core/models/models';
import { DeadlinesService } from '../deadlines/deadlines.service';
import { DeadlineFormDialogComponent } from '../deadlines/deadline-form-dialog';
import { AppointmentDialogComponent } from '../calendar/appointment-dialog';

/** Página do processo: dados, linha do tempo de movimentações e ações. */
@Component({
  selector: 'app-case-detail',
  imports: [RouterLink, MatButtonModule, MatIconModule, MatTooltipModule, LoadingComponent, EmptyStateComponent, BrDatePipe, CentsPipe],
  template: `
    <div class="page">
      <a routerLink="/processos" class="back"><mat-icon>arrow_back</mat-icon>Processos</a>
      @if (!legalCase()) {
        <app-loading />
      } @else {
        @let p = legalCase()!;
        <section class="hero card">
          <div class="hero-main">
            <span class="eyebrow">{{ p.areaLabel }}{{ p.proceduralClass ? ' · ' + p.proceduralClass : '' }}</span>
            <h1 class="mono">{{ p.cnjNumber || 'Processo não distribuído' }}</h1>
            <div class="parties">
              <a [routerLink]="['/clientes', p.clientId]" class="client">{{ p.clientName }}</a>
              <span class="vs">x</span>
              <span>{{ p.opposingParty || 'Parte contrária não informada' }}</span>
            </div>
            <div class="meta">
              <span class="badge" [class]="tone[p.status]">{{ p.statusLabel }}</span>
              @if (p.restricted) { <span class="badge gold"><mat-icon inline>lock</mat-icon>{{ p.area === 'CRIMINAL' ? 'Criminal — acesso restrito' : 'Sigiloso' }}</span> }
            </div>
          </div>
          <div class="hero-actions">
            @if (auth.has('CASES_WRITE') && !p.archived) {
              <button mat-stroked-button (click)="edit()"><mat-icon>edit</mat-icon>Editar</button>
              <button mat-flat-button (click)="addMovement()"><mat-icon>post_add</mat-icon>Andamento</button>
            }
            @if (auth.has('CASES_ARCHIVE')) {
              @if (p.archived) {
                <button mat-stroked-button (click)="unarchive()"><mat-icon>unarchive</mat-icon>Desarquivar</button>
              } @else {
                <button mat-icon-button (click)="archive()" matTooltip="Arquivar processo" aria-label="Arquivar"><mat-icon>archive</mat-icon></button>
              }
            }
          </div>
        </section>

        <div class="layout">
          <div class="side">
          <section class="card card-pad info">
            <div class="card-title"><mat-icon>info</mat-icon>Dados do processo</div>
            <dl>
              <dt>Tribunal</dt><dd>{{ p.court || '—' }}</dd>
              <dt>Vara</dt><dd>{{ p.courtDivision || '—' }}</dd>
              <dt>Comarca</dt><dd>{{ p.district || '—' }}{{ p.state ? '/' + p.state : '' }}</dd>
              <dt>Assunto</dt><dd>{{ p.subject || '—' }}</dd>
              <dt>Responsável</dt><dd>{{ p.responsibleName }}</dd>
              <dt>Valor da causa</dt><dd class="mono">{{ p.claimValueCents | cents }}</dd>
              <dt>Distribuição</dt><dd>{{ p.distributionDate | brDate }}</dd>
              <dt>Cadastro</dt><dd>{{ p.createdAt | brDate: true }}</dd>
              @if (p.archived) { <dt>Arquivado em</dt><dd>{{ p.archivedAt | brDate: true }}</dd> }
            </dl>
            @if (p.notes) {
              <div class="notes"><div class="card-title small-title">Observações</div><p>{{ p.notes }}</p></div>
            }
          </section>
          <section class="card card-pad">
            <div class="card-title"><mat-icon>alarm</mat-icon>Prazos pendentes
              <span class="spacer"></span>
              @if (auth.has('DEADLINES_WRITE') && !p.archived) {
                <button mat-icon-button (click)="newDeadline()" matTooltip="Novo prazo" aria-label="Novo prazo"><mat-icon>add_alarm</mat-icon></button>
              }
              @if (auth.has('CALENDAR_WRITE') && !p.archived) {
                <button mat-icon-button (click)="newHearing()" matTooltip="Agendar audiência" aria-label="Agendar audiência"><mat-icon>event</mat-icon></button>
              }
            </div>
            @if (!auth.has('DEADLINES_READ')) {
              <p class="muted small-p">Seu perfil não acessa o controle de prazos.</p>
            } @else if (deadlines().length === 0) {
              <p class="muted small-p">Nenhum prazo pendente.</p>
            } @else {
              <ul class="dl">
                @for (d of deadlines(); track d.id) {
                  <li>
                    <a [routerLink]="['/prazos']" [queryParams]="{ id: d.id }">{{ d.description }}</a>
                    <span class="mono" [class.late]="d.daysLeft <= 0">{{ d.dueDate | brDate }}</span>
                    @if (!d.validated) { <mat-icon class="pend" matTooltip="Contagem não validada">pending</mat-icon> }
                  </li>
                }
              </ul>
            }
          </section>
          </div>

          <section class="card card-pad">
            <div class="card-title"><mat-icon>timeline</mat-icon>Linha do tempo</div>
            @if (movements().length === 0) {
              <app-empty-state icon="timeline" title="Sem movimentações" />
            } @else {
              <ol class="timeline">
                @for (m of movements(); track m.id) {
                  <li [class.system]="m.type === 'SISTEMA'">
                    <span class="node"><mat-icon>{{ icons[m.type] }}</mat-icon></span>
                    <div class="t-body">
                      <div class="t-head">
                        <span class="badge" [class.gold]="m.type === 'DECISAO' || m.type === 'SENTENCA' || m.type === 'ACORDAO'">{{ labels[m.type] }}</span>
                        <strong>{{ m.title }}</strong>
                      </div>
                      @if (m.description) { <p class="pre">{{ m.description }}</p> }
                      <div class="muted small">{{ m.date | brDate }} · registrado por {{ m.authorName }}</div>
                    </div>
                  </li>
                }
              </ol>
              @if (cursor()) { <div class="more"><button mat-stroked-button (click)="loadMovements(true)">Carregar mais</button></div> }
            }
          </section>
        </div>
      }
    </div>
  `,
  styles: `
    .back { display: inline-flex; align-items: center; gap: 6px; color: var(--app-text-muted); text-decoration: none; margin-bottom: 14px; font-weight: 500; }
    .hero { display: flex; gap: 16px; padding: 22px 24px; flex-wrap: wrap; align-items: flex-start; margin-bottom: 16px;
      border-top: 3px solid var(--app-gold); }
    .hero-main { flex: 1 1 360px; min-width: 0; }
    .eyebrow { font-size: 11px; font-weight: 700; letter-spacing: 1.1px; text-transform: uppercase; color: var(--app-gold); }
    h1 { font-size: 24px; margin: 4px 0 6px; }
    .parties { display: flex; gap: 8px; align-items: baseline; flex-wrap: wrap; font-size: 15px; }
    .client { font-weight: 600; text-decoration: none; }
    .vs { color: var(--app-text-muted); font-style: italic; }
    .meta { display: flex; gap: 8px; margin-top: 10px; flex-wrap: wrap; }
    .hero-actions { display: flex; gap: 8px; align-items: center; }
    .layout { display: grid; grid-template-columns: 360px 1fr; gap: 16px; align-items: start; }
    .side { display: flex; flex-direction: column; gap: 16px; min-width: 0; }
    .card-title .spacer { flex: 1; }
    .small-p { margin: 0; font-size: 13px; }
    .dl { list-style: none; margin: 0; padding: 0; }
    .dl li { display: flex; gap: 8px; align-items: center; padding: 8px 0; border-bottom: 1px solid var(--app-border); font-size: 13px; }
    .dl li a { flex: 1; text-decoration: none; font-weight: 500; }
    .dl .late { color: var(--app-danger); font-weight: 600; }
    .dl .pend { font-size: 16px; width: 16px; height: 16px; color: var(--app-warning); }
    @media (max-width: 1000px) { .layout { grid-template-columns: 1fr; } }
    dl { display: grid; grid-template-columns: 120px 1fr; gap: 10px; margin: 0; }
    dt { color: var(--app-text-muted); font-size: 13px; }
    dd { margin: 0; font-weight: 500; overflow-wrap: anywhere; }
    .notes { margin-top: 18px; padding-top: 14px; border-top: 1px solid var(--app-border); }
    .notes p, .pre { white-space: pre-wrap; line-height: 1.5; margin: 6px 0 0; }
    .small-title { font-size: 13px; margin: 0; }
    .timeline { list-style: none; margin: 0; padding: 0; position: relative; }
    .timeline::before { content: ''; position: absolute; left: 17px; top: 8px; bottom: 8px; width: 2px; background: var(--app-border); }
    .timeline li { display: flex; gap: 14px; position: relative; padding-bottom: 20px; }
    .node { flex: 0 0 36px; width: 36px; height: 36px; border-radius: 50%; display: grid; place-items: center; z-index: 1;
      background: var(--app-surface); border: 2px solid var(--app-navy-2); color: var(--app-navy-2); }
    html.dark .node { border-color: #a9c4f0; color: #a9c4f0; }
    .node mat-icon { font-size: 18px; width: 18px; height: 18px; }
    li.system .node { border-color: var(--app-border); color: var(--app-text-muted); }
    li.system strong { font-weight: 500; color: var(--app-text-muted); }
    .t-body { flex: 1; min-width: 0; padding-top: 6px; }
    .t-head { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
    .small { font-size: 12px; margin-top: 4px; }
    .more { display: flex; justify-content: center; padding-top: 8px; }
  `,
})
export class CaseDetailComponent {
  readonly id = input.required<string>();
  readonly auth = inject(AuthService);
  private readonly service = inject(CasesService);
  private readonly dialog = inject(MatDialog);
  private readonly toast = inject(ToastService);
  private readonly router = inject(Router);
  readonly labels = MOVEMENT_LABELS;
  readonly icons = MOVEMENT_ICONS;
  readonly tone = CASE_STATUS_TONE;

  readonly legalCase = signal<LegalCase | null>(null);
  readonly movements = signal<CaseMovement[]>([]);
  readonly cursor = signal<string | null>(null);
  readonly deadlines = signal<Deadline[]>([]);
  private readonly deadlinesService = inject(DeadlinesService);

  constructor() {
    effect(() => {
      const id = this.id();
      this.legalCase.set(null);
      this.service.get(id).subscribe({
        next: (c) => {
          this.legalCase.set(c);
          this.loadMovements(false);
          this.loadDeadlines();
        },
        error: () => void this.router.navigate(['/processos']),
      });
    });
  }

  loadDeadlines(): void {
    if (!this.auth.has('DEADLINES_READ')) return;
    this.deadlinesService.list('PENDENTES', { caseId: this.id() }, null, 10).subscribe((page) => this.deadlines.set(page.items));
  }

  newDeadline(): void {
    const c = this.legalCase();
    if (!c) return;
    this.dialog
      .open(DeadlineFormDialogComponent, {
        data: { legalCase: { id: c.id, cnjNumber: c.cnjNumber, clientName: c.clientName, court: c.court } },
        width: '880px', maxWidth: '96vw',
      })
      .afterClosed()
      .subscribe((d?: Deadline) => {
        if (d) {
          this.toast.success('Prazo cadastrado.');
          this.loadDeadlines();
        }
      });
  }

  newHearing(): void {
    const c = this.legalCase();
    if (!c) return;
    this.dialog
      .open(AppointmentDialogComponent, {
        data: { legalCase: { id: c.id, cnjNumber: c.cnjNumber, clientName: c.clientName } },
        width: '820px', maxWidth: '96vw',
      })
      .afterClosed()
      .subscribe((a) => {
        if (a && a !== 'deleted') this.toast.success('Compromisso agendado.');
      });
  }

  loadMovements(more: boolean): void {
    this.service.movements(this.id(), more ? this.cursor() : null).subscribe((page) => {
      this.movements.set(more ? [...this.movements(), ...page.items] : page.items);
      this.cursor.set(page.nextCursor);
    });
  }

  edit(): void {
    this.dialog
      .open(CaseFormDialogComponent, { data: { legalCase: this.legalCase() }, width: '900px', maxWidth: '96vw' })
      .afterClosed()
      .subscribe((updated?: LegalCase) => {
        if (updated) {
          this.legalCase.set(updated);
          this.loadMovements(false);
          this.toast.success('Processo atualizado.');
        }
      });
  }

  addMovement(): void {
    this.dialog
      .open(MovementDialogComponent, { data: this.id(), width: '680px', maxWidth: '96vw' })
      .afterClosed()
      .subscribe((m?: CaseMovement) => {
        if (m) {
          this.loadMovements(false);
          this.toast.success('Andamento registrado.');
        }
      });
  }

  archive(): void {
    this.dialog
      .open(ConfirmDialogComponent, {
        data: { title: 'Arquivar processo', message: 'O processo ficará somente leitura até ser desarquivado. Deseja continuar?', confirmLabel: 'Arquivar' },
        width: '460px',
      })
      .afterClosed()
      .subscribe((ok?: boolean) => {
        if (ok) {
          this.service.archive(this.id()).subscribe((c) => {
            this.legalCase.set(c);
            this.loadMovements(false);
            this.toast.success('Processo arquivado.');
          });
        }
      });
  }

  unarchive(): void {
    this.service.unarchive(this.id()).subscribe((c) => {
      this.legalCase.set(c);
      this.loadMovements(false);
      this.toast.success('Processo desarquivado.');
    });
  }
}
