import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { MatTabsModule } from '@angular/material/tabs';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Router, RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';
import { AuditLog, CaseMovement, CaseRelated, Deadline, LegalCase } from '../../core/models/models';
import { ToastService } from '../../core/services/toast.service';
import { ConfirmDialogComponent } from '../../shared/components/confirm-dialog';
import { EmptyStateComponent, LoadingComponent } from '../../shared/components/ui';
import { CASE_STATUS_TONE, MOVEMENT_ICONS, MOVEMENT_LABELS } from '../../shared/labels';
import { BrDatePipe, CentsPipe, RelativeTimePipe } from '../../shared/pipes/format.pipes';
import { AppointmentDialogComponent } from '../calendar/appointment-dialog';
import { DeadlineFormDialogComponent } from '../deadlines/deadline-form-dialog';
import { DocumentsTableComponent } from '../documents/documents-table';
import { saveBlob } from '../finance/finance.service';
import { CaseFormDialogComponent } from './case-form-dialog';
import { CasesService } from './cases.service';
import { ApiService } from '../../core/api/api.service';
import { MovementDialogComponent } from './movement-dialog';

const DEADLINE_STATUS: Record<string, string> = { PENDENTE: 'Pendente', CONCLUIDO: 'Cumprido', CANCELADO: 'Cancelado' };
const CONTRACT_STATUS: Record<string, string> = { RASCUNHO: 'Rascunho', ATIVO: 'Ativo', ENCERRADO: 'Encerrado', CANCELADO: 'Cancelado' };
const TX_STATUS: Record<string, string> = { ABERTO: 'Em aberto', PAGO: 'Pago', CANCELADO: 'Cancelado' };

/** Página do processo em abas: resumo, partes, movimentações, prazos, audiências, documentos e demais vínculos. */
@Component({
  selector: 'app-case-detail',
  imports: [RouterLink, MatButtonModule, MatButtonToggleModule, MatIconModule, MatMenuModule, MatTabsModule, MatTooltipModule,
    LoadingComponent, EmptyStateComponent, BrDatePipe, CentsPipe, RelativeTimePipe, DocumentsTableComponent],
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
              <span>{{ p.opposingParty || p.passiveParty || 'Parte contrária não informada' }}</span>
            </div>
            <div class="meta">
              <span class="badge" [class]="tone[p.status]">{{ p.statusLabel }}</span>
              @if (p.phaseLabel) { <span class="badge info">{{ p.phaseLabel }}</span> }
              @if (p.freeJustice) { <span class="badge">Justiça gratuita</span> }
              @if (p.restricted) { <span class="badge gold"><mat-icon inline>lock</mat-icon>{{ p.area === 'CRIMINAL' ? 'Criminal — acesso restrito' : 'Segredo de justiça' }}</span> }
            </div>
          </div>
          <div class="hero-actions">
            @if (auth.has('CASES_WRITE') && !p.archived) {
              <button mat-stroked-button (click)="edit()"><mat-icon>edit</mat-icon>Editar</button>
              <button mat-flat-button (click)="addMovement()"><mat-icon>post_add</mat-icon>Andamento</button>
            }
            <button mat-icon-button [matMenuTriggerFor]="more" aria-label="Mais ações"><mat-icon>more_vert</mat-icon></button>
            <mat-menu #more="matMenu" xPosition="before">
              <button mat-menu-item (click)="printReport()"><mat-icon>print</mat-icon>Imprimir ficha/relatório</button>
              <button mat-menu-item (click)="downloadReport('pdf')"><mat-icon>picture_as_pdf</mat-icon>Relatório completo (PDF)</button>
              <button mat-menu-item (click)="downloadReport('xlsx')"><mat-icon>table_view</mat-icon>Relatório completo (Excel)</button>
              @if (auth.has('DOCUMENTS_READ')) {
                <a mat-menu-item routerLink="/editor-juridico" [queryParams]="{ caseId: p.id, caseLabel: (p.cnjNumber || 'Sem CNJ') + ' · ' + p.clientName }"><mat-icon>edit_document</mat-icon>Peças no Editor Jurídico</a>
              }
              @if (auth.has('BOARDS_USE')) {
                <a mat-menu-item routerLink="/tarefas" [queryParams]="{ caseId: p.id, label: p.cnjNumber || 'processo' }"><mat-icon>add_task</mat-icon>Tarefas do processo</a>
              }
              @if (auth.has('CASES_WRITE')) {
                <button mat-menu-item (click)="duplicate()"><mat-icon>content_copy</mat-icon>Duplicar cadastro</button>
              }
              @if (auth.has('CASES_ARCHIVE')) {
                @if (p.archived) {
                  <button mat-menu-item (click)="unarchive()"><mat-icon>unarchive</mat-icon>Desarquivar</button>
                } @else {
                  <button mat-menu-item (click)="archive()"><mat-icon>archive</mat-icon>Arquivar</button>
                }
              }
              @if (auth.has('CASES_DELETE') && p.archived) {
                <button mat-menu-item class="danger" (click)="remove()"><mat-icon>delete_forever</mat-icon>Excluir definitivamente</button>
              }
            </mat-menu>
          </div>
        </section>

        <mat-tab-group animationDuration="150ms" mat-stretch-tabs="false" class="tabs">
          <!-- 1. Resumo -->
          <mat-tab label="Resumo">
            <div class="grid4">
              <div class="kpi"><span>Prazos pendentes</span><strong>{{ pendingDeadlines().length }}</strong>
                @if (nextDeadline(); as n) { <small [class.late]="isLate(n.dueDate)">próximo: {{ n.dueDate | brDate }}</small> }</div>
              <div class="kpi"><span>Próxima audiência</span><strong>{{ nextHearing() ? (nextHearing()!.start | brDate) : '—' }}</strong>
                @if (nextHearing(); as h) { <small>{{ h.typeLabel }}</small> }</div>
              <div class="kpi"><span>Última movimentação</span><strong>{{ p.lastMovementAt ? (p.lastMovementAt | relative) : '—' }}</strong></div>
              <div class="kpi"><span>Sentença / trânsito</span><strong>{{ p.sentenceDate ? (p.sentenceDate | brDate) : '—' }}</strong>
                <small>{{ p.finalJudgmentDate ? 'trânsito em ' + (p.finalJudgmentDate | brDate) : 'sem trânsito registrado' }}</small></div>
            </div>
            <div class="two">
              <section class="card card-pad">
                <div class="card-title"><mat-icon>info</mat-icon>Dados do processo</div>
                <dl>
                  <dt>Tribunal</dt><dd>{{ p.court || '—' }}</dd>
                  <dt>Instância</dt><dd>{{ p.instance || '—' }}</dd>
                  <dt>Juízo</dt><dd>{{ p.judgingBody || '—' }}</dd>
                  <dt>Vara</dt><dd>{{ p.courtDivision || '—' }}</dd>
                  <dt>Comarca</dt><dd>{{ p.district || '—' }}{{ p.state ? '/' + p.state : '' }}</dd>
                  <dt>Assunto</dt><dd>{{ p.subject || '—' }}</dd>
                  @if (p.secondarySubjects?.length) { <dt>Outros assuntos</dt><dd>{{ p.secondarySubjects!.join('; ') }}</dd> }
                  <dt>Responsável</dt><dd>{{ p.responsibleName }}{{ p.lawyerOab ? ' · OAB ' + p.lawyerOab : '' }}</dd>
                  <dt>Valor da causa</dt><dd class="mono">{{ p.claimValueCents | cents }}</dd>
                  <dt>Distribuição</dt><dd>{{ p.distributionDate | brDate }}</dd>
                  @if (p.archived) { <dt>Arquivado em</dt><dd>{{ p.archivedAt | brDate: true }}</dd> }
                </dl>
              </section>
              <section class="card card-pad">
                <div class="card-title"><mat-icon>star</mat-icon>Movimentações importantes</div>
                @if (importantMovements().length === 0) {
                  <p class="muted">Nenhuma decisão, sentença ou movimentação marcada como importante.</p>
                } @else {
                  <ul class="list">
                    @for (m of importantMovements().slice(0, 6); track m.id) {
                      <li><span class="badge gold">{{ labels[m.type] }}</span> <strong>{{ m.title }}</strong><span class="muted small"> · {{ m.date | brDate }}</span></li>
                    }
                  </ul>
                }
              </section>
            </div>
          </mat-tab>

          <!-- 2. Partes -->
          <mat-tab label="Partes">
            <div class="two">
              <section class="card card-pad">
                <div class="card-title"><mat-icon>groups</mat-icon>Partes</div>
                <dl>
                  <dt>Cliente</dt><dd><a [routerLink]="['/clientes', p.clientId]">{{ p.clientName }}</a></dd>
                  <dt>Polo ativo</dt><dd>{{ p.activeParty || '—' }}</dd>
                  <dt>Polo passivo</dt><dd>{{ p.passiveParty || '—' }}</dd>
                  <dt>Parte contrária</dt><dd>{{ p.opposingParty || '—' }}</dd>
                </dl>
              </section>
              <section class="card card-pad">
                <div class="card-title"><mat-icon>badge</mat-icon>Patrocínio</div>
                <dl>
                  <dt>Advogado</dt><dd>{{ p.responsibleName }}</dd>
                  <dt>OAB</dt><dd>{{ p.lawyerOab || '—' }}</dd>
                  <dt>Justiça gratuita</dt><dd>{{ p.freeJustice ? 'Sim' : 'Não' }}</dd>
                  <dt>Segredo de justiça</dt><dd>{{ p.confidential ? 'Sim' : 'Não' }}</dd>
                </dl>
              </section>
            </div>
          </mat-tab>

          <!-- 3. Movimentações -->
          <mat-tab [label]="'Movimentações (' + movements().length + (cursor() ? '+' : '') + ')'">
            <section class="card card-pad tab-card">
              <div class="row filter-row">
                <mat-button-toggle-group [value]="onlyImportant()" (change)="onlyImportant.set($event.value)" aria-label="Filtro de movimentações">
                  <mat-button-toggle [value]="false">Todas</mat-button-toggle>
                  <mat-button-toggle [value]="true">Importantes</mat-button-toggle>
                </mat-button-toggle-group>
                <span class="spacer"></span>
                @if (auth.has('CASES_WRITE') && !p.archived) { <button mat-stroked-button (click)="addMovement()"><mat-icon>post_add</mat-icon>Registrar andamento</button> }
              </div>
              @if (visibleMovements().length === 0) {
                <app-empty-state icon="timeline" title="Sem movimentações" />
              } @else {
                <ol class="timeline">
                  @for (m of visibleMovements(); track m.id) {
                    <li [class.system]="m.type === 'SISTEMA'" [class.important]="m.important">
                      <span class="node"><mat-icon>{{ icons[m.type] }}</mat-icon></span>
                      <div class="t-body">
                        <div class="t-head">
                          <span class="badge" [class.gold]="m.important">{{ labels[m.type] }}</span>
                          <strong>{{ m.title }}</strong>
                          @if (m.important) { <mat-icon class="star" matTooltip="Importante">star</mat-icon> }
                        </div>
                        @if (m.description) { <p class="pre">{{ m.description }}</p> }
                        <div class="muted small">{{ m.date | brDate }} · {{ m.source === 'INTEGRACAO' ? 'importado por integração' : 'registrado por ' + m.authorName }}</div>
                      </div>
                    </li>
                  }
                </ol>
                @if (cursor()) { <div class="more"><button mat-stroked-button (click)="loadMovements(true)">Carregar mais</button></div> }
              }
            </section>
          </mat-tab>

          <!-- 4. Prazos -->
          @if (related()?.deadlines; as deadlines) {
            <mat-tab [label]="'Prazos (' + deadlines.length + ')'">
              <section class="card tab-card">
                <div class="tab-head">
                  <span class="muted">Prazos vinculados ao processo, inclusive cumpridos e cancelados.</span>
                  @if (auth.has('DEADLINES_WRITE') && !p.archived) { <button mat-stroked-button (click)="newDeadline()"><mat-icon>add_alarm</mat-icon>Novo prazo</button> }
                </div>
                @if (deadlines.length === 0) { <app-empty-state icon="alarm" title="Nenhum prazo" /> } @else {
                  <div class="table-wrap"><table class="data">
                    <thead><tr><th>Vencimento</th><th>Descrição</th><th>Responsável</th><th>Situação</th></tr></thead>
                    <tbody>
                      @for (d of deadlines; track d.id) {
                        <tr class="clickable" [routerLink]="['/prazos']" [queryParams]="{ id: d.id }">
                          <td class="mono" [class.late]="d.status === 'PENDENTE' && isLate(d.dueDate)">{{ d.dueDate | brDate }}</td>
                          <td>{{ d.description }} @if (!d.validated && d.status === 'PENDENTE') { <span class="badge warning">validar contagem</span> }</td>
                          <td>{{ d.responsibleName }}</td>
                          <td><span class="badge" [class.success]="d.status === 'CONCLUIDO'">{{ deadlineStatus[d.status] || d.status }}</span></td>
                        </tr>
                      }
                    </tbody>
                  </table></div>
                }
              </section>
            </mat-tab>
          }

          <!-- 5. Audiências -->
          @if (related()?.appointments; as appointments) {
            <mat-tab [label]="'Audiências (' + appointments.length + ')'">
              <section class="card tab-card">
                <div class="tab-head">
                  <span class="muted">Audiências, perícias, sessões e demais compromissos do processo.</span>
                  @if (auth.has('CALENDAR_WRITE') && !p.archived) { <button mat-stroked-button (click)="newHearing()"><mat-icon>event</mat-icon>Agendar</button> }
                </div>
                @if (appointments.length === 0) { <app-empty-state icon="event" title="Nenhum compromisso" /> } @else {
                  <div class="table-wrap"><table class="data">
                    <thead><tr><th>Data</th><th>Tipo</th><th>Título</th><th>Local</th></tr></thead>
                    <tbody>
                      @for (a of appointments; track a.id) {
                        <tr><td class="mono">{{ a.start | brDate: true }}</td><td><span class="badge">{{ a.typeLabel }}</span></td><td>{{ a.title }}</td><td>{{ a.location || '—' }}</td></tr>
                      }
                    </tbody>
                  </table></div>
                }
              </section>
            </mat-tab>
          }

          <!-- 6. Documentos -->
          @if (auth.has('DOCUMENTS_READ')) {
            <mat-tab [label]="'Documentos (' + (related()?.documents ?? 0) + ')'">
              <ng-template matTabContent>
                <div class="tab-card">
                  <app-documents-table [caseId]="p.id" [legalCase]="{ id: p.id, label: (p.cnjNumber || 'Sem CNJ') + ' · ' + p.clientName }" [compact]="true" />
                </div>
              </ng-template>
            </mat-tab>
          }

          <!-- 7. Tarefas -->
          @if (related()?.cards; as cards) {
            <mat-tab [label]="'Tarefas (' + cards.length + ')'">
              <section class="card tab-card">
                <div class="tab-head">
                  <span class="muted">Cartões dos quadros vinculados a este processo.</span>
                  <a mat-stroked-button routerLink="/quadros"><mat-icon>view_kanban</mat-icon>Abrir quadros</a>
                </div>
                @if (cards.length === 0) { <app-empty-state icon="task_alt" title="Nenhuma tarefa vinculada" text="No cartão do quadro, vincule este processo para vê-lo aqui." /> } @else {
                  <div class="table-wrap"><table class="data">
                    <thead><tr><th>Tarefa</th><th>Quadro / coluna</th><th>Vencimento</th><th>Situação</th></tr></thead>
                    <tbody>
                      @for (t of cards; track t.id) {
                        <tr class="clickable" [routerLink]="['/quadros', t.boardId]" [queryParams]="{ card: t.id }">
                          <td>{{ t.title }}</td><td>{{ t.boardName }} · {{ t.columnName }}</td>
                          <td class="mono" [class.late]="!t.completed && t.dueDate && isLate(t.dueDate)">{{ t.dueDate | brDate }}</td>
                          <td><span class="badge" [class.success]="t.completed">{{ t.completed ? 'Concluída' : 'Em andamento' }}</span></td>
                        </tr>
                      }
                    </tbody>
                  </table></div>
                }
              </section>
            </mat-tab>
          }

          <!-- 8. Financeiro -->
          @if (related()?.transactions; as tx) {
            <mat-tab [label]="'Financeiro (' + tx.length + ')'">
              <section class="card tab-card">
                <div class="tab-head">
                  <span>Receitas: <strong class="mono ok">{{ sum(tx, 'RECEITA') | cents }}</strong> · Despesas/custas: <strong class="mono bad">{{ sum(tx, 'DESPESA') | cents }}</strong></span>
                  <a mat-stroked-button routerLink="/financeiro"><mat-icon>account_balance_wallet</mat-icon>Financeiro</a>
                </div>
                @if (tx.length === 0) { <app-empty-state icon="payments" title="Sem lançamentos vinculados" /> } @else {
                  <div class="table-wrap"><table class="data">
                    <thead><tr><th>Vencimento</th><th>Descrição</th><th>Categoria</th><th>Valor</th><th>Situação</th></tr></thead>
                    <tbody>
                      @for (t of tx; track t.id) {
                        <tr><td class="mono">{{ t.dueDate | brDate }}</td><td>{{ t.description }}</td><td>{{ t.categoryLabel }}</td>
                          <td class="mono" [class.ok]="t.direction === 'RECEITA'" [class.bad]="t.direction === 'DESPESA'">{{ t.direction === 'DESPESA' ? '−' : '' }}{{ t.amountCents | cents }}</td>
                          <td>{{ txStatus[t.status] || t.status }}</td></tr>
                      }
                    </tbody>
                  </table></div>
                }
              </section>
            </mat-tab>
          }

          <!-- 9. Contratos -->
          @if (related()?.contracts; as contracts) {
            <mat-tab [label]="'Contratos (' + contracts.length + ')'">
              <section class="card tab-card">
                @if (contracts.length === 0) { <app-empty-state icon="handshake" title="Nenhum contrato vinculado" /> } @else {
                  <div class="table-wrap"><table class="data">
                    <thead><tr><th>Número</th><th>Título</th><th>Situação</th></tr></thead>
                    <tbody>
                      @for (c of contracts; track c.id) {
                        <tr class="clickable" [routerLink]="['/contratos', c.id]"><td class="mono">{{ c.number }}</td><td>{{ c.title }}</td>
                          <td><span class="badge" [class.success]="c.status === 'ATIVO'">{{ contractStatus[c.status] || c.status }}</span></td></tr>
                      }
                    </tbody>
                  </table></div>
                }
              </section>
            </mat-tab>
          }

          <!-- 10. Histórico -->
          <mat-tab label="Histórico">
            <ng-template matTabContent>
              <div class="two tab-card">
                <section class="card card-pad">
                  <div class="card-title"><mat-icon>history</mat-icon>Alterações e acessos</div>
                  @if (history().length === 0) { <p class="muted">Sem registros.</p> } @else {
                    <ul class="list">
                      @for (h of history(); track h.id) {
                        <li><strong>{{ h.summary }}</strong><div class="muted small">{{ h.at | brDate: true }} · {{ h.actorEmail || h.actorUid }}</div></li>
                      }
                    </ul>
                    @if (historyCursor()) { <div class="more"><button mat-stroked-button (click)="loadHistory(true)">Carregar mais</button></div> }
                  }
                </section>
                <section class="card card-pad">
                  <div class="card-title"><mat-icon>badge</mat-icon>Histórico de responsáveis</div>
                  @if (!p.responsibleHistory?.length) { <p class="muted">{{ p.responsibleName }} (desde o cadastro).</p> } @else {
                    <ul class="list">
                      @for (r of p.responsibleHistory!; track $index) {
                        <li><strong>{{ r.name }}</strong><div class="muted small">{{ r.from | brDate }} → {{ r.to ? (r.to | brDate) : 'atual' }}</div></li>
                      }
                    </ul>
                  }
                </section>
              </div>
            </ng-template>
          </mat-tab>

          <!-- 11. Observações -->
          <mat-tab label="Observações">
            <section class="card card-pad tab-card">
              <div class="card-title"><mat-icon>notes</mat-icon>Observações internas
                <span class="spacer"></span>
                @if (auth.has('CASES_WRITE') && !p.archived) { <button mat-button (click)="edit()"><mat-icon>edit</mat-icon>Editar</button> }
              </div>
              <p class="pre">{{ p.notes || 'Sem observações internas.' }}</p>
            </section>
          </mat-tab>

        </mat-tab-group>
      }
    </div>
  `,
  styles: `
    .back { display: inline-flex; align-items: center; gap: 6px; color: var(--app-text-muted); text-decoration: none; margin-bottom: 14px; font-weight: 500; }
    .hero { display: flex; gap: 16px; padding: 22px 24px; flex-wrap: wrap; align-items: flex-start; margin-bottom: 8px; border-top: 3px solid var(--app-gold); }
    .hero-main { flex: 1 1 360px; min-width: 0; }
    .eyebrow { font-size: 11px; font-weight: 700; letter-spacing: 1.1px; text-transform: uppercase; color: var(--app-gold); }
    h1 { font-size: 24px; margin: 4px 0 6px; overflow-wrap: anywhere; }
    .parties { display: flex; gap: 8px; align-items: baseline; flex-wrap: wrap; font-size: 15px; }
    .client { font-weight: 600; text-decoration: none; }
    .vs { color: var(--app-text-muted); font-style: italic; }
    .meta { display: flex; gap: 8px; margin-top: 10px; flex-wrap: wrap; }
    .hero-actions { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
    .tab-card { margin-top: 16px; }
    .grid4 { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; margin-top: 16px; }
    @media (max-width: 1000px) { .grid4 { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
    .kpi { background: var(--app-surface); border: 1px solid var(--app-border); border-radius: var(--app-radius); padding: 14px 16px; display: flex; flex-direction: column; gap: 2px; }
    .kpi span { font-size: 12px; color: var(--app-text-muted); }
    .kpi strong { font-size: 20px; }
    .kpi small { font-size: 12px; color: var(--app-text-muted); }
    .two { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; margin-top: 16px; }
    @media (max-width: 900px) { .two { grid-template-columns: 1fr; } }
    dl { display: grid; grid-template-columns: 140px 1fr; gap: 10px; margin: 0; }
    dt { color: var(--app-text-muted); font-size: 13px; }
    dd { margin: 0; font-weight: 500; overflow-wrap: anywhere; }
    .list { list-style: none; margin: 0; padding: 0; }
    .list li { padding: 8px 0; border-bottom: 1px solid var(--app-border); }
    .tab-head { display: flex; gap: 10px; align-items: center; justify-content: space-between; flex-wrap: wrap; padding: 12px 16px; border-bottom: 1px solid var(--app-border); }
    .filter-row { margin-bottom: 14px; }
    .late { color: var(--app-danger); font-weight: 600; }
    .ok { color: var(--app-success); }
    .bad { color: var(--app-danger); }
    .danger { color: var(--app-danger); }
    .pre { white-space: pre-wrap; line-height: 1.5; margin: 6px 0 0; }
    .timeline { list-style: none; margin: 0; padding: 0; position: relative; }
    .timeline::before { content: ''; position: absolute; left: 17px; top: 8px; bottom: 8px; width: 2px; background: var(--app-border); }
    .timeline li { display: flex; gap: 14px; position: relative; padding-bottom: 20px; }
    .node { flex: 0 0 36px; width: 36px; height: 36px; border-radius: 50%; display: grid; place-items: center; z-index: 1;
      background: var(--app-surface); border: 2px solid var(--app-navy-2); color: var(--app-navy-2); }
    html.dark .node { border-color: #a9c4f0; color: #a9c4f0; }
    li.important .node { border-color: var(--app-gold); color: #9b7a3f; background: var(--app-gold-soft); }
    .node mat-icon { font-size: 18px; width: 18px; height: 18px; }
    li.system .node { border-color: var(--app-border); color: var(--app-text-muted); }
    li.system strong { font-weight: 500; color: var(--app-text-muted); }
    .star { font-size: 16px; width: 16px; height: 16px; color: var(--app-gold); }
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
  readonly deadlineStatus = DEADLINE_STATUS;
  readonly contractStatus = CONTRACT_STATUS;
  readonly txStatus = TX_STATUS;

  readonly legalCase = signal<LegalCase | null>(null);
  readonly movements = signal<CaseMovement[]>([]);
  readonly cursor = signal<string | null>(null);
  readonly related = signal<CaseRelated | null>(null);
  readonly history = signal<AuditLog[]>([]);
  readonly historyCursor = signal<string | null>(null);
  readonly onlyImportant = signal(false);

  readonly importantMovements = computed(() => this.movements().filter((m) => m.important));
  readonly visibleMovements = computed(() => (this.onlyImportant() ? this.importantMovements() : this.movements()));
  private readonly api = inject(ApiService);

  readonly pendingDeadlines = computed(() => (this.related()?.deadlines ?? []).filter((d) => d.status === 'PENDENTE'));
  readonly nextDeadline = computed(() => [...this.pendingDeadlines()].sort((a, b) => a.dueDate.localeCompare(b.dueDate))[0] ?? null);
  readonly nextHearing = computed(() => {
    const now = Date.now();
    return [...(this.related()?.appointments ?? [])].filter((a) => new Date(a.start).getTime() >= now)
      .sort((a, b) => a.start.localeCompare(b.start))[0] ?? null;
  });

  constructor() {
    effect(() => {
      const id = this.id();
      this.legalCase.set(null);
      this.service.get(id).subscribe({
        next: (c) => {
          this.legalCase.set(c);
          this.loadMovements(false);
          this.loadRelated();
          this.loadHistory(false);
        },
        error: () => void this.router.navigate(['/processos']),
      });
    });
  }

  isLate(date: string | null): boolean {
    return !!date && date < new Date().toISOString().slice(0, 10);
  }

  sum(tx: NonNullable<CaseRelated['transactions']>, direction: 'RECEITA' | 'DESPESA'): number {
    return tx.filter((t) => t.direction === direction && t.status !== 'CANCELADO').reduce((s, t) => s + t.amountCents, 0);
  }

  loadRelated(): void {
    this.service.related(this.id()).subscribe((r) => this.related.set(r));
  }

  loadHistory(more: boolean): void {
    this.service.history(this.id(), more ? this.historyCursor() : null).subscribe((page) => {
      this.history.set(more ? [...this.history(), ...page.items] : page.items);
      this.historyCursor.set(page.nextCursor);
    });
  }

  loadMovements(more: boolean): void {
    this.service.movements(this.id(), more ? this.cursor() : null).subscribe((page) => {
      this.movements.set(more ? [...this.movements(), ...page.items] : page.items);
      this.cursor.set(page.nextCursor);
    });
  }

  newDeadline(): void {
    const c = this.legalCase();
    if (!c) return;
    this.dialog.open(DeadlineFormDialogComponent, {
      data: { legalCase: { id: c.id, cnjNumber: c.cnjNumber, clientName: c.clientName, court: c.court } }, width: '880px', maxWidth: '96vw',
    }).afterClosed().subscribe((d?: Deadline) => {
      if (d) { this.toast.success('Prazo cadastrado.'); this.loadRelated(); }
    });
  }

  newHearing(): void {
    const c = this.legalCase();
    if (!c) return;
    this.dialog.open(AppointmentDialogComponent, {
      data: { legalCase: { id: c.id, cnjNumber: c.cnjNumber, clientName: c.clientName } }, width: '820px', maxWidth: '96vw',
    }).afterClosed().subscribe((a) => {
      if (a && a !== 'deleted') { this.toast.success('Compromisso agendado.'); this.loadRelated(); }
    });
  }

  edit(): void {
    this.dialog.open(CaseFormDialogComponent, { data: { legalCase: this.legalCase() }, width: '900px', maxWidth: '96vw' })
      .afterClosed().subscribe((updated?: LegalCase) => {
        if (updated) {
          this.legalCase.set(updated);
          this.loadMovements(false);
          this.loadHistory(false);
          this.toast.success('Processo atualizado.');
        }
      });
  }

  addMovement(): void {
    this.dialog.open(MovementDialogComponent, { data: this.id(), width: '680px', maxWidth: '96vw' })
      .afterClosed().subscribe((m?: CaseMovement) => {
        if (m) {
          this.loadMovements(false);
          // Sentença, recurso, suspensão e trânsito atualizam a situação/fase do processo.
          this.service.get(this.id()).subscribe((c) => this.legalCase.set(c));
          this.toast.success('Andamento registrado.');
        }
      });
  }

  downloadReport(format: 'pdf' | 'xlsx'): void {
    this.service.report(this.id(), format).subscribe({
      next: (r) => saveBlob(r, `processo.${format}`),
      error: () => this.toast.error('Não foi possível gerar o relatório.'),
    });
  }

  printReport(): void {
    const win = window.open('', '_blank');
    this.service.report(this.id(), 'pdf').subscribe({
      next: (r) => {
        const url = URL.createObjectURL(r.body as Blob);
        if (win) {
          win.location.href = url;
          win.addEventListener('load', () => win.print(), { once: true });
        } else {
          saveBlob(r, 'processo.pdf');
        }
        setTimeout(() => URL.revokeObjectURL(url), 120_000);
      },
      error: () => { win?.close(); this.toast.error('Não foi possível gerar o relatório.'); },
    });
  }

  duplicate(): void {
    this.dialog.open(ConfirmDialogComponent, {
      data: { title: 'Duplicar cadastro', message: 'Será criado um novo processo com os mesmos dados (sem número CNJ, movimentações e vínculos).', confirmLabel: 'Duplicar' },
      width: '460px',
    }).afterClosed().subscribe((ok) => {
      if (ok) this.service.duplicate(this.id()).subscribe((c) => { this.toast.success('Cadastro duplicado.'); void this.router.navigate(['/processos', c.id]); });
    });
  }

  archive(): void {
    this.dialog.open(ConfirmDialogComponent, {
      data: { title: 'Arquivar processo', message: 'O processo ficará somente leitura até ser desarquivado. Deseja continuar?', confirmLabel: 'Arquivar' },
      width: '460px',
    }).afterClosed().subscribe((ok?: boolean) => {
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

  /** Exclusão só de processo arquivado sem vínculos (política de retenção). */
  remove(): void {
    this.service.links(this.id()).subscribe((links) => {
      const labels: Record<string, string> = { deadlines: 'prazo(s)', appointments: 'compromisso(s)', documents: 'documento(s)',
        financial_transactions: 'lançamento(s)', contracts: 'contrato(s)' };
      const blocking = Object.entries(links).filter(([, n]) => n > 0).map(([k, n]) => `${n} ${labels[k] ?? k}`);
      if (blocking.length) {
        this.dialog.open(ConfirmDialogComponent, {
          data: { title: 'Exclusão não permitida', message: `O processo possui ${blocking.join(', ')}. Mantenha-o arquivado (obrigação de guarda).`, confirmLabel: 'Entendi' },
          width: '460px',
        });
        return;
      }
      this.dialog.open(ConfirmDialogComponent, {
        data: { title: 'Excluir processo', message: 'O processo e suas movimentações serão excluídos definitivamente. A exclusão fica registrada na auditoria.',
          confirmLabel: 'Excluir', danger: true, reasonLabel: 'Motivo da exclusão', reasonRequired: true },
        width: '480px',
      }).afterClosed().subscribe((reason?: string) => {
        if (typeof reason === 'string' && reason) {
          this.service.delete(this.id(), reason).subscribe(() => { this.toast.success('Processo excluído.'); void this.router.navigate(['/processos']); });
        }
      });
    });
  }
}
