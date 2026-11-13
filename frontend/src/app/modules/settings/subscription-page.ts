import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { Router, RouterLink } from '@angular/router';
import { ToastService } from '../../core/services/toast.service';
import { EmptyStateComponent, PageHeaderComponent, StatCardComponent } from '../../shared/components/ui';
import { BrDatePipe, CentsPipe } from '../../shared/pipes/format.pipes';
import { saveBlob } from '../finance/finance.service';
import {
  BillingCycle, CYCLE_LABELS, INVOICE_LABELS, MODULE_LABELS, OfficeService, PlanInfo, SUBSCRIPTION_LABELS, SubscriptionInvoice,
  SubscriptionOverview, formatBytes, formatMb,
} from './office.service';

/** Plano e Assinatura do escritório: situação, uso, faturas, forma de pagamento e pedido de mudança de plano. */
@Component({
  selector: 'app-subscription-page',
  imports: [FormsModule, RouterLink, MatButtonModule, MatButtonToggleModule, MatFormFieldModule, MatIconModule, MatInputModule,
    PageHeaderComponent, StatCardComponent, EmptyStateComponent, BrDatePipe, CentsPipe],
  template: `
    <div class="page">
      <app-page-header eyebrow="Administração" title="Plano e Assinatura" subtitle="Situação da assinatura, limites do plano, faturas e pagamento." />

      @if (data(); as d) {
        @let s = d.subscription;
        @if (s.status === 'EM_ATRASO' || s.status === 'SUSPENSA') {
          <div class="card alert" [class.danger]="s.status === 'SUSPENSA'">
            <mat-icon>{{ s.status === 'SUSPENSA' ? 'block' : 'warning' }}</mat-icon>
            <p>@if (s.status === 'SUSPENSA') { O acesso da equipe está suspenso por falta de pagamento. Após a confirmação do pagamento o acesso é liberado automaticamente. }
              @else { Há fatura vencida. Após {{ s.graceDays ?? 10 }} dias do vencimento o acesso da equipe é suspenso até a regularização. }</p>
          </div>
        }

        <div class="stats">
          <app-stat-card label="Plano" [value]="s.planName || s.planCode || '—'" icon="workspace_premium" [accent]="true"
            [hint]="s.status ? subLabels[s.status] + (s.billingCycle ? ' · ' + cycleLabels[s.billingCycle] : '') : 'sem assinatura configurada'" />
          <app-stat-card label="Valor" [value]="s.priceCents !== null ? (s.priceCents | cents) : 'a definir'" icon="payments"
            [hint]="s.billingCycle === 'ANUAL' ? 'por ano' : s.priceCents !== null ? 'por mês' : 'combinado com a plataforma'" />
          <app-stat-card label="Usuários" [value]="s.users + (s.maxUsers ? ' / ' + s.maxUsers : '')" icon="group"
            [hint]="s.maxUsers ? 'limite do plano' : 'sem limite'" [tone]="s.maxUsers && s.users >= s.maxUsers ? 'warning' : ''" />
          <app-stat-card label="Armazenamento" [value]="bytes(s.storageBytes)" icon="cloud" [hint]="'de ' + mb(s.maxStorageMb)"
            [tone]="s.maxStorageMb && s.storageBytes >= s.maxStorageMb * 1048576 * 0.9 ? 'warning' : ''" />
          <app-stat-card [label]="s.status === 'TRIAL' ? 'Avaliação até' : 'Próxima fatura'" icon="event"
            [value]="s.status === 'TRIAL' ? (s.trialEndsAt | brDate) : (s.nextInvoiceDate | brDate)" />
        </div>

        <div class="two">
          <section class="card">
            <div class="head"><h3>Faturas</h3></div>
            @if (d.invoices.length === 0) {
              <app-empty-state icon="receipt" title="Nenhuma fatura emitida" text="As faturas da assinatura aparecem aqui quando forem emitidas pela plataforma." />
            } @else {
              <div class="table-wrap">
                <table class="data">
                  <thead><tr><th>Fatura</th><th>Período</th><th>Vencimento</th><th>Valor</th><th>Situação</th><th></th></tr></thead>
                  <tbody>
                    @for (i of d.invoices; track i.id) {
                      <tr>
                        <td class="mono">{{ i.number }}<div class="muted small">{{ i.planName }} · {{ cycleLabels[i.cycle] }}</div></td>
                        <td class="muted small">{{ i.periodStart | brDate }} a {{ i.periodEnd | brDate }}</td>
                        <td>{{ i.dueDate | brDate }}</td>
                        <td class="mono">{{ i.amountCents | cents }}</td>
                        <td><span class="badge" [class.success]="i.status === 'PAGA'" [class.danger]="i.status === 'VENCIDA'" [class.gold]="i.status === 'ABERTA'">{{ invLabels[i.status] }}</span>
                          @if (i.paidDate) { <div class="muted small">pago em {{ i.paidDate | brDate }}</div> }</td>
                        <td><button mat-icon-button (click)="pdf(i)" aria-label="Baixar fatura em PDF"><mat-icon>picture_as_pdf</mat-icon></button></td>
                      </tr>
                    }
                  </tbody>
                </table>
              </div>
            }
          </section>

          <section class="card pad">
            <h3>Como pagar</h3>
            @let p = d.payment;
            @if (p.pixKey || p.bankInfo || p.instructions) {
              @if (p.issuerName) { <p class="small"><strong>Favorecido:</strong> {{ p.issuerName }}{{ p.issuerDocument ? ' · ' + p.issuerDocument : '' }}</p> }
              @if (p.pixKey) { <p class="small"><strong>Chave Pix:</strong> <span class="mono">{{ p.pixKey }}</span>
                <button mat-icon-button (click)="copy(p.pixKey)" aria-label="Copiar chave Pix"><mat-icon>content_copy</mat-icon></button></p> }
              @if (p.bankInfo) { <p class="small pre"><strong>Dados bancários:</strong><br />{{ p.bankInfo }}</p> }
              @if (p.instructions) { <p class="small pre">{{ p.instructions }}</p> }
              @if (p.contactEmail) { <p class="small muted">Envie o comprovante para {{ p.contactEmail }} ou pelo <a routerLink="/suporte">Suporte</a>.</p> }
            } @else {
              <p class="muted small">A forma de pagamento ainda não foi informada pela plataforma. Fale com o <a routerLink="/suporte">Suporte</a>.</p>
            }
            <p class="muted small">O pagamento é confirmado manualmente pela equipe da plataforma; a fatura passa a “Paga” após a conferência.</p>
          </section>
        </div>

        <section class="card pad">
          <div class="head flat">
            <h3>Planos disponíveis</h3>
            <mat-button-toggle-group [(ngModel)]="cycle" aria-label="Ciclo de cobrança">
              <mat-button-toggle value="MENSAL">Mensal</mat-button-toggle>
              <mat-button-toggle value="ANUAL">Anual</mat-button-toggle>
            </mat-button-toggle-group>
          </div>
          <div class="plans">
            @for (plan of d.plans; track plan.code) {
              <div class="plan" [class.current]="plan.code === s.planCode" [class.highlight]="plan.highlight">
                <div class="plan-head">
                  <strong>{{ plan.name }}</strong>
                  @if (plan.code === s.planCode) { <span class="badge success">Plano atual</span> }
                  @else if (plan.highlight) { <span class="badge gold">Recomendado</span> }
                </div>
                <div class="price">{{ priceOf(plan) !== null ? (priceOf(plan) | cents) : 'Sob consulta' }}
                  @if (priceOf(plan) !== null) { <span class="muted small">/{{ cycle === 'ANUAL' ? 'ano' : 'mês' }}</span> }</div>
                @if (plan.description) { <p class="muted small">{{ plan.description }}</p> }
                <ul>
                  <li><mat-icon>group</mat-icon>{{ plan.maxUsers ? 'Até ' + plan.maxUsers + ' usuários' : 'Usuários ilimitados' }}</li>
                  <li><mat-icon>cloud</mat-icon>{{ mb(plan.maxStorageMb) }} de armazenamento</li>
                  @for (m of plan.modules; track m) { <li><mat-icon>check</mat-icon>{{ moduleLabels[m] || m }}</li> }
                </ul>
                @if (plan.code !== s.planCode || cycle !== s.billingCycle) {
                  <button mat-stroked-button (click)="request(plan)" [disabled]="sending()"><mat-icon>swap_horiz</mat-icon>Solicitar este plano</button>
                }
              </div>
            }
          </div>
          @if (chosen(); as c) {
            <div class="request">
              <mat-form-field class="grow"><mat-label>Observação para a plataforma (opcional)</mat-label>
                <textarea matInput [(ngModel)]="note" rows="2" maxlength="2000"></textarea></mat-form-field>
              <button mat-button (click)="chosen.set(null)">Cancelar</button>
              <button mat-flat-button (click)="send()" [disabled]="sending()"><mat-icon>send</mat-icon>Enviar pedido: {{ c.name }} ({{ cycle === 'ANUAL' ? 'anual' : 'mensal' }})</button>
            </div>
          }
          <p class="muted small">A mudança de plano é registrada como chamado no Suporte e aplicada pela plataforma; você é avisado pela resposta do chamado.</p>
        </section>
      }
    </div>
  `,
  styles: `
    .stats { display: grid; grid-template-columns: repeat(auto-fit, minmax(190px, 1fr)); gap: 16px; margin-bottom: 16px; }
    .alert { display: flex; gap: 12px; align-items: center; padding: 12px 16px; margin-bottom: 16px; border-left: 4px solid var(--app-warning, #b7791f); }
    .alert.danger { border-left-color: var(--app-danger); }
    .alert p { margin: 0; }
    .two { display: grid; grid-template-columns: minmax(0, 2fr) minmax(260px, 1fr); gap: 16px; margin-bottom: 16px; align-items: start; }
    .pad { padding: 16px 20px; }
    .head { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 14px 16px; border-bottom: 1px solid var(--app-border); flex-wrap: wrap; }
    .head.flat { padding: 0 0 12px; border: 0; }
    h3 { margin: 0 0 8px; font-size: 15px; }
    .head h3 { margin: 0; }
    .small { font-size: 12px; }
    .pre { white-space: pre-line; }
    .plans { display: grid; grid-template-columns: repeat(auto-fill, minmax(240px, 1fr)); gap: 16px; }
    .plan { border: 1px solid var(--app-border); border-radius: 14px; padding: 16px; display: flex; flex-direction: column; gap: 8px; }
    .plan.highlight { border-color: var(--app-gold); }
    .plan.current { box-shadow: 0 0 0 2px var(--app-gold) inset; }
    .plan-head { display: flex; justify-content: space-between; align-items: center; gap: 8px; }
    .price { font-size: 22px; font-weight: 700; }
    .plan p { margin: 0; }
    .plan ul { list-style: none; padding: 0; margin: 4px 0; display: flex; flex-direction: column; gap: 4px; flex: 1; }
    .plan li { display: flex; gap: 6px; align-items: center; font-size: 13px; }
    .plan li mat-icon { font-size: 18px; width: 18px; height: 18px; color: var(--app-gold); }
    .request { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; margin-top: 16px; }
    .grow { flex: 1 1 320px; }
    @media (max-width: 900px) { .two { grid-template-columns: minmax(0, 1fr); } }
  `,
})
export class SubscriptionPageComponent {
  private readonly office = inject(OfficeService);
  private readonly toast = inject(ToastService);
  private readonly router = inject(Router);
  readonly subLabels = SUBSCRIPTION_LABELS as Record<string, string>;
  readonly invLabels = INVOICE_LABELS;
  readonly cycleLabels = CYCLE_LABELS as Record<string, string>;
  readonly moduleLabels = MODULE_LABELS;
  readonly data = signal<SubscriptionOverview | null>(null);
  readonly chosen = signal<PlanInfo | null>(null);
  readonly sending = signal(false);
  cycle: BillingCycle = 'MENSAL';
  note = '';
  bytes = formatBytes;
  mb = formatMb;

  constructor() {
    this.office.subscription().subscribe((d) => {
      this.data.set(d);
      this.cycle = d.subscription.billingCycle ?? 'MENSAL';
    });
  }

  priceOf(p: PlanInfo): number | null {
    return this.cycle === 'ANUAL' ? p.yearlyPriceCents : p.monthlyPriceCents;
  }

  pdf(i: SubscriptionInvoice): void {
    this.office.invoicePdf(i.id).subscribe((r) => saveBlob(r, `${i.number}.pdf`));
  }

  copy(text: string): void {
    void navigator.clipboard?.writeText(text).then(() => this.toast.success('Chave Pix copiada.'));
  }

  request(p: PlanInfo): void {
    this.chosen.set(p);
    this.note = '';
  }

  send(): void {
    const p = this.chosen();
    if (!p) return;
    this.sending.set(true);
    this.office.requestChange(p.code, this.cycle, this.note.trim() || null).subscribe({
      next: (t) => {
        this.sending.set(false);
        this.chosen.set(null);
        this.toast.success(`Pedido enviado (chamado ${t.number}).`);
        void this.router.navigate(['/suporte'], { queryParams: { chamado: t.id } });
      },
      error: () => this.sending.set(false),
    });
  }
}
