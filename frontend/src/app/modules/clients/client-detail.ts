import { Component, inject, input, signal, effect } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { MatTabsModule } from '@angular/material/tabs';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Router, RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';
import { Client, ClientInteraction, LegalCase } from '../../core/models/models';
import { ToastService } from '../../core/services/toast.service';
import { ConfirmData, ConfirmDialogComponent } from '../../shared/components/confirm-dialog';
import { EmptyStateComponent, LoadingComponent } from '../../shared/components/ui';
import { CASE_STATUS_TONE, CHANNEL_LABELS } from '../../shared/labels';
import { BrDatePipe, CentsPipe } from '../../shared/pipes/format.pipes';
import { CaseFormDialogComponent } from '../cases/case-form-dialog';
import { ClientFormDialogComponent } from './client-form-dialog';
import { ClientsService } from './clients.service';
import { InteractionDialogComponent } from './interaction-dialog';
import { TransactionsTableComponent } from '../finance/transactions-table';
import { DocumentsTableComponent } from '../documents/documents-table';

/** Página individual do cliente: dados, processos vinculados e histórico de atendimentos. */
@Component({
  selector: 'app-client-detail',
  imports: [
    RouterLink, MatButtonModule, MatIconModule, MatTabsModule, MatMenuModule, MatTooltipModule, LoadingComponent,
    EmptyStateComponent, BrDatePipe, CentsPipe, TransactionsTableComponent, DocumentsTableComponent,
  ],
  template: `
    <div class="page">
      <a routerLink="/clientes" class="back"><mat-icon>arrow_back</mat-icon>Clientes</a>
      @if (!client()) {
        <app-loading />
      } @else {
        @let c = client()!;
        <section class="hero card">
          <div class="avatar" [class.pj]="c.type === 'PJ'"><mat-icon>{{ c.type === 'PJ' ? 'apartment' : 'person' }}</mat-icon></div>
          <div class="hero-main">
            <h1>{{ c.name }}</h1>
            <div class="meta">
              <span class="mono">{{ c.document }}</span>
              <span class="badge" [class.success]="c.status === 'ACTIVE'">{{ c.status === 'ACTIVE' ? 'Ativo' : 'Inativo' }}</span>
              @if (c.defaulting) { <span class="badge danger">Inadimplente</span> }
              @if (!c.sensitiveVisible) { <span class="badge" matTooltip="Seu perfil não visualiza dados pessoais completos"><mat-icon inline>lock</mat-icon>Dados protegidos</span> }
            </div>
          </div>
          <div class="hero-actions">
            @if (auth.has('CLIENTS_WRITE')) {
              <button mat-stroked-button (click)="edit()"><mat-icon>edit</mat-icon>Editar</button>
              <button mat-flat-button (click)="addInteraction()"><mat-icon>support_agent</mat-icon>Atendimento</button>
            }
            @if (auth.has('CLIENTS_WRITE') || auth.has('CLIENTS_INACTIVATE')) {
              <button mat-icon-button [matMenuTriggerFor]="more" aria-label="Mais ações"><mat-icon>more_vert</mat-icon></button>
              <mat-menu #more="matMenu">
                @if (auth.has('CASES_WRITE')) {
                  <button mat-menu-item (click)="newCase()"><mat-icon>gavel</mat-icon>Novo processo para o cliente</button>
                }
                @if (auth.has('CLIENTS_WRITE')) {
                  <button mat-menu-item (click)="toggleDefaulting()">
                    <mat-icon>{{ c.defaulting ? 'task_alt' : 'report' }}</mat-icon>{{ c.defaulting ? 'Remover inadimplência' : 'Marcar como inadimplente' }}
                  </button>
                }
                @if (auth.has('CLIENTS_INACTIVATE')) {
                  @if (c.status === 'ACTIVE') {
                    <button mat-menu-item (click)="inactivate()"><mat-icon>person_off</mat-icon>Inativar cliente</button>
                  } @else {
                    <button mat-menu-item (click)="reactivate()"><mat-icon>person</mat-icon>Reativar cliente</button>
                  }
                }
              </mat-menu>
            }
          </div>
        </section>

        @if (c.status === 'INACTIVE') {
          <div class="notice"><mat-icon>info</mat-icon>Inativado em {{ c.inactivatedAt | brDate: true }} — {{ c.inactivationReason }}</div>
        }

        <mat-tab-group animationDuration="150ms" mat-stretch-tabs="false">
          <mat-tab label="Dados cadastrais">
            <div class="grid-info">
              <section class="card card-pad">
                <div class="card-title"><mat-icon>badge</mat-icon>Identificação</div>
                <dl>
                  <dt>Tipo</dt><dd>{{ c.type === 'PJ' ? 'Pessoa jurídica' : 'Pessoa física' }}</dd>
                  <dt>{{ c.type === 'PJ' ? 'CNPJ' : 'CPF' }}</dt><dd class="mono">{{ c.document }}</dd>
                  @if (c.type === 'PJ') {
                    <dt>Nome fantasia</dt><dd>{{ c.tradeName || '—' }}</dd>
                  } @else {
                    <dt>RG</dt><dd>{{ c.rg || '—' }}</dd>
                    <dt>Nascimento</dt><dd>{{ c.birthDate | brDate }}</dd>
                    <dt>Estado civil</dt><dd>{{ c.maritalStatus || '—' }}</dd>
                    <dt>Profissão</dt><dd>{{ c.profession || '—' }}</dd>
                  }
                </dl>
              </section>
              <section class="card card-pad">
                <div class="card-title"><mat-icon>contact_phone</mat-icon>Contato</div>
                <dl>
                  <dt>Telefone</dt><dd>{{ c.phone || '—' }}</dd>
                  <dt>WhatsApp</dt>
                  <dd>
                    @if (c.whatsapp) { <a [href]="whatsLink(c.whatsapp)" target="_blank" rel="noopener noreferrer">{{ c.whatsapp }}</a> } @else { — }
                  </dd>
                  <dt>E-mail</dt><dd>@if (c.email) { <a [href]="'mailto:' + c.email">{{ c.email }}</a> } @else { — }</dd>
                </dl>
              </section>
              <section class="card card-pad">
                <div class="card-title"><mat-icon>home_work</mat-icon>Endereço</div>
                @if (c.address) {
                  <p class="addr">
                    {{ c.address.street }}{{ c.address.number ? ', ' + c.address.number : '' }}{{ c.address.complement ? ' — ' + c.address.complement : '' }}<br />
                    {{ c.address.neighborhood }}{{ c.address.city ? ' · ' + c.address.city : '' }}{{ c.address.state ? '/' + c.address.state : '' }}<br />
                    @if (c.address.zipCode) { CEP {{ c.address.zipCode }} }
                  </p>
                } @else { <p class="muted">Não informado.</p> }
              </section>
              <section class="card card-pad">
                <div class="card-title"><mat-icon>notes</mat-icon>Observações</div>
                <p class="pre">{{ c.notes || 'Sem observações.' }}</p>
                <p class="muted small">Cadastrado em {{ c.createdAt | brDate: true }} · atualizado em {{ c.updatedAt | brDate: true }}</p>
              </section>
            </div>
          </mat-tab>

          @if (auth.has('CASES_READ')) {
            <mat-tab [label]="'Processos (' + cases().length + (casesCursor() ? '+' : '') + ')'">
              <div class="card tab-card">
                @if (cases().length === 0) {
                  <app-empty-state icon="gavel" title="Nenhum processo vinculado" />
                } @else {
                  <div class="table-wrap">
                    <table class="data">
                      <thead><tr><th>Número CNJ</th><th>Área</th><th>Parte contrária</th><th>Responsável</th><th>Valor da causa</th><th>Situação</th></tr></thead>
                      <tbody>
                        @for (p of cases(); track p.id) {
                          <tr class="clickable" [routerLink]="['/processos', p.id]">
                            <td class="mono">{{ p.cnjNumber || 'Não distribuído' }}</td>
                            <td>{{ p.areaLabel }}</td>
                            <td>{{ p.opposingParty || '—' }}</td>
                            <td>{{ p.responsibleName }}</td>
                            <td class="mono">{{ p.claimValueCents | cents }}</td>
                            <td><span class="badge" [class]="tone[p.status]">{{ p.statusLabel }}</span></td>
                          </tr>
                        }
                      </tbody>
                    </table>
                  </div>
                  @if (casesCursor()) { <div class="more"><button mat-stroked-button (click)="loadCases(true)">Carregar mais</button></div> }
                }
              </div>
            </mat-tab>
          }

          <mat-tab label="Atendimentos">
            <div class="card tab-card">
              @if (interactions().length === 0) {
                <app-empty-state icon="support_agent" title="Nenhum atendimento registrado" />
              } @else {
                <ul class="timeline">
                  @for (i of interactions(); track i.id) {
                    <li>
                      <span class="dot"></span>
                      <div class="t-head"><strong>{{ i.subject }}</strong><span class="badge">{{ channel[i.channel] }}</span></div>
                      @if (i.description) { <p class="pre">{{ i.description }}</p> }
                      <div class="muted small">{{ i.occurredAt | brDate: true }} · {{ i.userName }}</div>
                    </li>
                  }
                </ul>
                @if (interactionsCursor()) { <div class="more"><button mat-stroked-button (click)="loadInteractions(true)">Carregar mais</button></div> }
              }
            </div>
          </mat-tab>

          @if (auth.has('FINANCE_READ')) {
            <mat-tab label="Financeiro">
              <div class="tab-card"><app-transactions-table direction="RECEITA" [clientId]="c.id" [client]="{ id: c.id, name: c.name }" /></div>
            </mat-tab>
          }

          @if (auth.has('DOCUMENTS_READ')) {
            <mat-tab label="Documentos">
              <div class="tab-card"><app-documents-table [clientId]="c.id" [client]="{ id: c.id, name: c.name }" [compact]="true" /></div>
            </mat-tab>
          }
        </mat-tab-group>
      }
    </div>
  `,
  styles: `
    .back { display: inline-flex; align-items: center; gap: 6px; color: var(--app-text-muted); text-decoration: none; margin-bottom: 14px; font-weight: 500; }
    .back:hover { color: var(--app-text); }
    .hero { display: flex; gap: 18px; align-items: center; padding: 22px 24px; flex-wrap: wrap; margin-bottom: 16px; }
    .avatar { width: 60px; height: 60px; border-radius: 16px; display: grid; place-items: center; background: linear-gradient(135deg, var(--app-navy), var(--app-navy-2)); color: var(--app-gold); }
    .avatar mat-icon { font-size: 30px; width: 30px; height: 30px; }
    .hero-main { flex: 1 1 300px; min-width: 0; }
    h1 { font-size: 24px; font-weight: 700; }
    .meta { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; margin-top: 6px; color: var(--app-text-muted); }
    .hero-actions { display: flex; gap: 8px; align-items: center; }
    .notice { display: flex; gap: 8px; align-items: center; padding: 12px 16px; border-radius: 10px; background: var(--app-warning-bg); color: var(--app-warning); margin-bottom: 16px; }
    .grid-info { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; padding-top: 16px; }
    @media (max-width: 900px) { .grid-info { grid-template-columns: 1fr; } }
    dl { display: grid; grid-template-columns: 140px 1fr; gap: 10px 12px; margin: 0; }
    dt { color: var(--app-text-muted); font-size: 13px; }
    dd { margin: 0; font-weight: 500; overflow-wrap: anywhere; }
    .addr { line-height: 1.6; margin: 0; }
    .pre { white-space: pre-wrap; margin: 4px 0 0; line-height: 1.5; }
    .small { font-size: 12px; }
    .tab-card { margin-top: 16px; }
    .more { display: flex; justify-content: center; padding: 16px; border-top: 1px solid var(--app-border); }
    .timeline { list-style: none; margin: 0; padding: 20px 24px 20px 40px; position: relative; }
    .timeline::before { content: ''; position: absolute; left: 25px; top: 24px; bottom: 24px; width: 2px; background: var(--app-border); }
    .timeline li { position: relative; padding: 0 0 22px; }
    .dot { position: absolute; left: -21px; top: 4px; width: 12px; height: 12px; border-radius: 50%; background: var(--app-gold); border: 2px solid var(--app-surface); }
    .t-head { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
  `,
})
export class ClientDetailComponent {
  readonly id = input.required<string>();
  readonly auth = inject(AuthService);
  private readonly service = inject(ClientsService);
  private readonly dialog = inject(MatDialog);
  private readonly toast = inject(ToastService);
  private readonly router = inject(Router);
  readonly channel = CHANNEL_LABELS;
  readonly tone = CASE_STATUS_TONE;

  readonly client = signal<Client | null>(null);
  readonly cases = signal<LegalCase[]>([]);
  readonly casesCursor = signal<string | null>(null);
  readonly interactions = signal<ClientInteraction[]>([]);
  readonly interactionsCursor = signal<string | null>(null);

  constructor() {
    effect(() => {
      const id = this.id();
      this.client.set(null);
      this.service.get(id).subscribe({
        next: (c) => {
          this.client.set(c);
          this.loadInteractions(false);
          if (this.auth.has('CASES_READ')) this.loadCases(false);
        },
        error: () => void this.router.navigate(['/clientes']),
      });
    });
  }

  loadCases(more: boolean): void {
    this.service.cases(this.id(), more ? this.casesCursor() : null).subscribe((page) => {
      this.cases.set(more ? [...this.cases(), ...page.items] : page.items);
      this.casesCursor.set(page.nextCursor);
    });
  }

  loadInteractions(more: boolean): void {
    this.service.interactions(this.id(), more ? this.interactionsCursor() : null).subscribe((page) => {
      this.interactions.set(more ? [...this.interactions(), ...page.items] : page.items);
      this.interactionsCursor.set(page.nextCursor);
    });
  }

  whatsLink(phone: string): string {
    const digits = phone.replace(/\D/g, '');
    return `https://wa.me/${digits.length <= 11 ? '55' + digits : digits}`;
  }

  edit(): void {
    this.dialog
      .open(ClientFormDialogComponent, { data: this.client(), width: '860px', maxWidth: '96vw' })
      .afterClosed()
      .subscribe((c?: Client) => {
        if (c) {
          this.client.set(c);
          this.toast.success('Cadastro atualizado.');
        }
      });
  }

  addInteraction(): void {
    this.dialog
      .open(InteractionDialogComponent, { data: this.id(), width: '640px', maxWidth: '96vw' })
      .afterClosed()
      .subscribe((i?: ClientInteraction) => {
        if (i) {
          this.loadInteractions(false);
          this.toast.success('Atendimento registrado.');
        }
      });
  }

  newCase(): void {
    const c = this.client();
    this.dialog
      .open(CaseFormDialogComponent, { data: { client: c ? { id: c.id, name: c.name } : null }, width: '900px', maxWidth: '96vw' })
      .afterClosed()
      .subscribe((created?: LegalCase) => {
        if (created) {
          this.toast.success('Processo cadastrado.');
          void this.router.navigate(['/processos', created.id]);
        }
      });
  }

  toggleDefaulting(): void {
    const c = this.client();
    if (!c) return;
    this.service.setDefaulting(c.id, !c.defaulting).subscribe((updated) => {
      this.client.set(updated);
      this.toast.success(updated.defaulting ? 'Cliente marcado como inadimplente.' : 'Inadimplência removida.');
    });
  }

  inactivate(): void {
    const data: ConfirmData = {
      title: 'Inativar cliente',
      message: 'O cliente deixará de aparecer nas listagens padrão. O histórico é preservado e a ação pode ser revertida.',
      confirmLabel: 'Inativar',
      danger: true,
      reasonLabel: 'Motivo da inativação',
      reasonRequired: true,
    };
    this.dialog.open(ConfirmDialogComponent, { data, width: '480px' }).afterClosed().subscribe((reason?: string) => {
      if (typeof reason === 'string' && reason) {
        this.service.inactivate(this.id(), reason).subscribe((c) => {
          this.client.set(c);
          this.toast.success('Cliente inativado.');
        });
      }
    });
  }

  reactivate(): void {
    this.service.reactivate(this.id()).subscribe((c) => {
      this.client.set(c);
      this.toast.success('Cliente reativado.');
    });
  }
}
