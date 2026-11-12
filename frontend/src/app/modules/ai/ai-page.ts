import { Component, ElementRef, computed, inject, signal, viewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { ApiService } from '../../core/api/api.service';
import { AuthService } from '../../core/auth/auth.service';
import { CursorPage, LegalCase } from '../../core/models/models';
import { StoredDocument } from '../documents/documents.service';
import { ToastService } from '../../core/services/toast.service';
import { ClientPickerComponent, PickedClient } from '../../shared/components/client-picker';
import { ConfirmDialogComponent, ConfirmData } from '../../shared/components/confirm-dialog';
import { EmptyStateComponent, PageHeaderComponent } from '../../shared/components/ui';
import { BrDatePipe } from '../../shared/pipes/format.pipes';
import { DeadlineFormDialogComponent } from '../deadlines/deadline-form-dialog';

interface Option { value: string; label: string; description: string; available: boolean }
interface TaskOption { value: string; label: string; documentsRequired: number; caseRequired: boolean }
interface Catalog { configured: boolean; enabled: boolean; model: string; agents: Option[]; tasks: TaskOption[]; officialDomains: string[] }
interface Source { url: string; title: string | null; citedText: string | null }
interface Extracted { date: string | null; time: string | null; kind: string | null; description: string | null; sourceQuote: string | null; confidence: string | null }
interface Turn {
  role: 'user' | 'assistant'; text: string; at: string; model: string | null; stopReason: string | null; sources: Source[];
  extraction: Extracted[]; reviewed: boolean; reviewedByName: string | null; draftId: string | null;
}
interface Conversation {
  id: string; agent: string; task: string; title: string; caseId: string | null; caseNumber: string | null; clientId: string | null;
  clientName: string | null; documentTitles: string[]; lastMessageAt: string; messages: Turn[]; inputTokens: number; outputTokens: number;
}

/** Tarefas oferecidas por agente (o servidor aceita qualquer combinação válida; a lista só organiza a tela). */
const AGENT_TASKS: Record<string, string[]> = {
  ASSISTENTE_JURIDICO: ['CONVERSA', 'RESUMO_PROCESSO', 'MOVIMENTACOES_RELEVANTES', 'MINUTA', 'ESTRUTURA_PETICAO', 'RELATORIO'],
  ANALISADOR_DOCUMENTOS: ['RESUMO_DOCUMENTO', 'ANALISE_CONTRATO', 'EXTRACAO_DATAS', 'COMPARACAO', 'CONVERSA'],
  ASSISTENTE_CRIMINAL: ['CONVERSA', 'RESUMO_PROCESSO', 'MOVIMENTACOES_RELEVANTES', 'EXTRACAO_DATAS', 'MINUTA'],
  PESQUISADOR: ['PESQUISA'],
  ADMINISTRATIVO: ['CONVERSA', 'RELATORIO', 'MINUTA'],
  FINANCEIRO: ['CONVERSA', 'RELATORIO'],
};

/** Markdown simples (títulos, listas, negrito, itálico, links http/https) a partir de texto já escapado. */
export function markdown(md: string): string {
  const esc = (s: string) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  const inline = (s: string) => esc(s)
    .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
    .replace(/(^|[^*\w])\*(?!\s)(.+?)(?<!\s)\*(?![*\w])/g, '$1<em>$2</em>')
    .replace(/\[([^\]]+)\]\((https?:\/\/[^\s)]+)\)/g, '<a href="$2" target="_blank" rel="noopener noreferrer">$1</a>')
    .replace(/(^|[\s(])(https?:\/\/[^\s<)]+)/g, '$1<a href="$2" target="_blank" rel="noopener noreferrer">$2</a>');
  const out: string[] = [];
  let list: 'ul' | 'ol' | null = null;
  let para: string[] = [];
  const flush = () => { if (para.length) { out.push('<p>' + para.join('<br>') + '</p>'); para = []; } };
  const close = () => { if (list) { out.push(`</${list}>`); list = null; } };
  for (const raw of (md ?? '').replace(/\r/g, '').split('\n')) {
    const line = raw.trim();
    const h = /^(#{1,3})\s+(.*)$/.exec(line);
    const ul = /^[-*•]\s+(.*)$/.exec(line);
    const ol = /^\d+[.)]\s+(.*)$/.exec(line);
    if (h || ul || ol || !line) flush();
    if (!ul && !ol) close();
    if (h) out.push(`<h${h[1].length + 2}>${inline(h[2])}</h${h[1].length + 2}>`);
    else if (ul || ol) {
      const kind = ul ? 'ul' : 'ol';
      if (list !== kind) { close(); out.push(`<${kind}>`); list = kind; }
      out.push(`<li>${inline((ul ?? ol)![1])}</li>`);
    } else if (line && !/^-{3,}$/.test(line)) para.push(inline(line));
  }
  flush();
  close();
  return out.join('');
}

/** IA Jurídica e Pesquisa Jurídica (mesma tela; a pesquisa usa somente fontes oficiais com links). */
@Component({
  selector: 'app-ai-page',
  imports: [FormsModule, RouterLink, MatButtonModule, MatIconModule, MatFormFieldModule, MatInputModule, MatSelectModule, MatProgressBarModule,
    MatTooltipModule, PageHeaderComponent, EmptyStateComponent, BrDatePipe, ClientPickerComponent],
  template: `
    <div class="page">
      <app-page-header eyebrow="Inteligência" [title]="research ? 'Pesquisa Jurídica' : 'IA Jurídica'"
        [subtitle]="research ? 'Pesquisa em legislação e tribunais somente em sites oficiais, com o link de cada fonte.'
          : 'Resumos, análise de documentos e contratos, extração de datas, minutas e estrutura de peças — sempre para revisão do advogado.'">
        <button mat-stroked-button (click)="newConversation()"><mat-icon>add_comment</mat-icon>Nova conversa</button>
      </app-page-header>

      @if (catalog(); as cat) {
        @if (!cat.enabled) {
          <section class="card pad notice">
            <mat-icon>smart_toy</mat-icon>
            <div>
              <strong>A IA jurídica ainda não está ativada neste escritório.</strong>
              <p class="muted">{{ cat.configured ? 'A chave já está configurada no servidor; falta o administrador ativar o conector.'
                : 'É preciso configurar a chave da Anthropic (no servidor ou no conector) e ativar o conector.' }}
                Ao ativar, o escritório decide enviar o conteúdo dos processos e documentos escolhidos à Anthropic, que atua como operadora
                (os dados da API não são usados para treinamento). CPF e CNPJ vão mascarados.</p>
              @if (auth.has('SETTINGS_MANAGE')) { <a mat-flat-button routerLink="/integracoes"><mat-icon>hub</mat-icon>Ir para Integrações</a> }
            </div>
          </section>
        } @else {
          <div class="layout">
            <!-- ============================================================ conversas -->
            <aside class="card side">
              <div class="side-head"><strong>Minhas conversas</strong></div>
              <ul class="convs">
                @for (c of conversations(); track c.id) {
                  <li [class.active]="current()?.id === c.id" (click)="open(c.id)" tabindex="0" (keydown.enter)="open(c.id)">
                    <span class="t">{{ c.title }}</span>
                    <span class="muted small">{{ agentLabel(c.agent) }} · {{ c.lastMessageAt | brDate: true }}</span>
                  </li>
                } @empty { <li class="muted small empty">Nenhuma conversa ainda.</li> }
              </ul>
            </aside>

            <!-- ============================================================ conversa / início -->
            <section class="card main">
              @if (busy()) { <mat-progress-bar mode="indeterminate" /> }
              @if (current(); as c) {
                <div class="conv-head">
                  <div>
                    <strong>{{ c.title }}</strong>
                    <div class="muted small">{{ agentLabel(c.agent) }} · {{ taskLabel(c.task) }}
                      {{ c.caseNumber ? '· processo ' + c.caseNumber : '' }}{{ c.clientName && !c.caseNumber ? '· ' + c.clientName : '' }}
                      {{ c.documentTitles.length ? '· ' + c.documentTitles.join(', ') : '' }}</div>
                  </div>
                  <span class="spacer"></span>
                  <button mat-icon-button (click)="remove(c)" matTooltip="Excluir conversa" aria-label="Excluir conversa"><mat-icon>delete</mat-icon></button>
                </div>
                <div class="msgs" #scroller>
                  @for (m of c.messages; track $index; let i = $index) {
                    @if (m.role === 'user') {
                      <div class="msg user"><div class="bubble">{{ m.text }}</div></div>
                    } @else {
                      <div class="msg ai">
                        <div class="ai-card">
                          <div class="ai-label"><mat-icon>auto_awesome</mat-icon>Rascunho gerado por IA — revise antes de usar
                            @if (m.reviewed) { <span class="badge success">revisado por {{ m.reviewedByName }}</span> }</div>
                          <div class="md" [innerHTML]="render(m.text)"></div>
                          @if (m.extraction.length) {
                            <div class="table-wrap"><table class="data stack">
                              <thead><tr><th>Data</th><th>Tipo</th><th>O quê</th><th>Trecho</th><th></th></tr></thead>
                              <tbody>
                                @for (e of m.extraction; track $index) {
                                  <tr>
                                    <td data-label="Data" class="nowrap">{{ e.date ? (e.date | brDate) : '—' }}{{ e.time ? ' ' + e.time : '' }}</td>
                                    <td data-label="Tipo">{{ (e.kind || '').toLowerCase() }}<div class="muted small">{{ e.confidence ? 'confiança ' + e.confidence.toLowerCase() : '' }}</div></td>
                                    <td data-label="O quê">{{ e.description }}</td>
                                    <td data-label="Trecho" class="muted small">“{{ e.sourceQuote }}”</td>
                                    <td>@if (auth.has('DEADLINES_WRITE') && c.caseId) {
                                      <button mat-stroked-button (click)="createDeadline(c, e)"><mat-icon>event</mat-icon>Criar prazo</button> }</td>
                                  </tr>
                                }
                              </tbody>
                            </table></div>
                            @if (!c.caseId) { <p class="muted small">Para criar prazos a partir das datas, inicie a extração escolhendo também o processo.</p> }
                          }
                          @if (m.sources.length) {
                            <div class="sources">
                              <strong class="small">Fontes consultadas</strong>
                              <ol>
                                @for (s of m.sources; track s.url) {
                                  <li><a [href]="s.url" target="_blank" rel="noopener noreferrer">{{ s.title || s.url }}</a>
                                    @if (s.citedText) { <div class="muted small">“{{ s.citedText }}”</div> }</li>
                                }
                              </ol>
                            </div>
                          }
                          <div class="ai-actions">
                            <button mat-button (click)="copy(m.text)"><mat-icon>content_copy</mat-icon>Copiar</button>
                            @if (!m.reviewed) { <button mat-button (click)="review(c, i)"><mat-icon>fact_check</mat-icon>Marcar como revisado</button> }
                            @if (auth.has('DOCUMENTS_WRITE') && !m.extraction.length) {
                              @if (m.draftId) { <a mat-button [routerLink]="['/editor-juridico', m.draftId]"><mat-icon>edit_document</mat-icon>Abrir no Editor</a> }
                              @else { <button mat-button (click)="toEditor(c, i)"><mat-icon>edit_document</mat-icon>Enviar ao Editor Jurídico</button> }
                            }
                          </div>
                        </div>
                      </div>
                    }
                  }
                </div>
                <form class="composer" (ngSubmit)="ask()">
                  <mat-form-field appearance="outline" subscriptSizing="dynamic" class="grow">
                    <mat-label>{{ research ? 'Nova pergunta de pesquisa' : 'Continue a conversa' }}</mat-label>
                    <textarea matInput name="q" [(ngModel)]="question" rows="2" maxlength="20000" (keydown.control.enter)="ask()"></textarea>
                  </mat-form-field>
                  <button mat-flat-button type="submit" [disabled]="busy() || !question.trim()"><mat-icon>send</mat-icon>Enviar</button>
                </form>
              } @else {
                <!-- ============================================================ nova conversa -->
                <div class="start">
                  <h3>{{ research ? 'Nova pesquisa' : 'Nova conversa' }}</h3>
                  @if (!research) {
                    <div class="grid">
                      <mat-form-field><mat-label>Agente</mat-label>
                        <mat-select [(ngModel)]="agent" (selectionChange)="task = tasksFor(agent)[0]?.value ?? 'CONVERSA'">
                          @for (a of agentsAvailable(); track a.value) { <mat-option [value]="a.value">{{ a.label }}</mat-option> }
                        </mat-select>
                        <mat-hint>{{ agentDescription() }}</mat-hint>
                      </mat-form-field>
                      <mat-form-field><mat-label>Tarefa</mat-label>
                        <mat-select [(ngModel)]="task">
                          @for (t of tasksFor(agent); track t.value) { <mat-option [value]="t.value">{{ t.label }}</mat-option> }
                        </mat-select>
                      </mat-form-field>
                      @if (needsContext()) {
                        <div class="c2"><app-client-picker label="Cliente (para escolher processo e documentos)" (picked)="pickClient($event)" /></div>
                        <mat-form-field><mat-label>Processo{{ taskInfo()?.caseRequired ? '' : ' (opcional)' }}</mat-label>
                          <mat-select [(ngModel)]="caseId" (selectionChange)="loadDocuments()" [disabled]="!cases().length">
                            <mat-option [value]="''">Nenhum</mat-option>
                            @for (k of cases(); track k.id) { <mat-option [value]="k.id">{{ k.cnjNumber || 'Sem número' }} · {{ k.subject || k.area }}</mat-option> }
                          </mat-select>
                          @if (client && !cases().length) { <mat-hint>Cliente sem processos.</mat-hint> }
                        </mat-form-field>
                        <mat-form-field><mat-label>Documentos{{ (taskInfo()?.documentsRequired ?? 0) ? ' (' + taskInfo()!.documentsRequired + ' obrigatório(s))' : ' (opcional)' }}</mat-label>
                          <mat-select [(ngModel)]="documentIds" multiple [disabled]="!documents().length">
                            @for (d of documents(); track d.id) { <mat-option [value]="d.id">{{ d.title }}</mat-option> }
                          </mat-select>
                          <mat-hint>PDF, DOCX ou TXT; PDFs digitalizados são lidos como imagem. Até 4.</mat-hint>
                        </mat-form-field>
                      }
                    </div>
                  }
                  <mat-form-field appearance="outline" class="full">
                    <mat-label>{{ research ? 'O que você quer pesquisar?' : 'Pergunta ou orientação (opcional nas tarefas prontas)' }}</mat-label>
                    <textarea matInput [(ngModel)]="question" rows="4" maxlength="20000"></textarea>
                    @if (research) { <mat-hint>Fontes permitidas: {{ cat.officialDomains.join(', ') }} (e subdomínios).</mat-hint> }
                  </mat-form-field>
                  <div class="row">
                    <span class="muted small">As respostas são rascunhos: a IA não inventa jurisprudência de propósito, mas pode errar — confira as fontes.
                      CPF e CNPJ são enviados mascarados.</span>
                    <span class="spacer"></span>
                    <button mat-flat-button (click)="ask()" [disabled]="busy() || !canStart()"><mat-icon>auto_awesome</mat-icon>{{ research ? 'Pesquisar' : 'Gerar' }}</button>
                  </div>
                </div>
              }
            </section>
          </div>
        }
      } @else {
        <app-empty-state icon="smart_toy" title="Carregando…" />
      }
    </div>
  `,
  styles: `
    .pad { padding: 16px 20px; }
    .notice { display: flex; gap: 16px; align-items: flex-start; }
    .notice mat-icon { font-size: 32px; width: 32px; height: 32px; color: var(--app-accent, #b8955a); }
    .layout { display: grid; grid-template-columns: 280px minmax(0, 1fr); gap: 16px; align-items: start; }
    .side { max-height: calc(100vh - 220px); overflow: auto; }
    .side-head { padding: 12px 16px; border-bottom: 1px solid var(--app-border); }
    .convs { list-style: none; margin: 0; padding: 0; }
    .convs li { padding: 10px 16px; border-bottom: 1px solid var(--app-border); cursor: pointer; display: flex; flex-direction: column; gap: 2px; }
    .convs li.active, .convs li:hover { background: var(--app-surface-2); }
    .convs li.empty { cursor: default; }
    .convs .t { font-weight: 500; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .main { display: flex; flex-direction: column; min-height: 480px; }
    .conv-head { display: flex; align-items: center; gap: 8px; padding: 12px 16px; border-bottom: 1px solid var(--app-border); }
    .msgs { flex: 1; padding: 16px; display: flex; flex-direction: column; gap: 14px; overflow: auto; max-height: calc(100vh - 330px); }
    .msg.user { display: flex; justify-content: flex-end; }
    .bubble { background: var(--app-primary, #0f2742); color: #fff; padding: 10px 14px; border-radius: 14px 14px 2px 14px; max-width: 80%; white-space: pre-wrap; }
    .ai-card { border: 1px solid var(--app-border); border-radius: 12px; padding: 12px 16px; }
    .ai-label { display: flex; align-items: center; gap: 6px; font-size: 12px; color: var(--app-text-muted); margin-bottom: 6px; flex-wrap: wrap; }
    .ai-label mat-icon { font-size: 16px; width: 16px; height: 16px; color: var(--app-accent, #b8955a); }
    .md { line-height: 1.6; overflow-wrap: anywhere; }
    .md :is(h3, h4, h5) { margin: 12px 0 4px; }
    .sources { margin-top: 10px; padding-top: 8px; border-top: 1px dashed var(--app-border); }
    .sources ol { margin: 4px 0 0; padding-left: 20px; }
    .ai-actions { display: flex; gap: 4px; flex-wrap: wrap; margin-top: 8px; }
    .composer { display: flex; gap: 12px; align-items: center; padding: 12px 16px; border-top: 1px solid var(--app-border); }
    .grow { flex: 1; }
    .start { padding: 20px; }
    .start h3 { margin: 0 0 12px; }
    .grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 4px 12px; }
    .c2 { grid-column: span 2; }
    .grid mat-form-field, .full { width: 100%; }
    .row { display: flex; gap: 12px; align-items: center; flex-wrap: wrap; }
    .small { font-size: 12px; }
    .nowrap { white-space: nowrap; }
    .badge.success { background: var(--app-success-bg); color: var(--app-success); }
    @media (max-width: 900px) {
      .layout { grid-template-columns: minmax(0, 1fr); }
      .side { max-height: 220px; }
      .grid { grid-template-columns: minmax(0, 1fr); }
      .c2 { grid-column: span 1; }
      .bubble { max-width: 92%; }
    }
  `,
})
export class AiPageComponent {
  readonly auth = inject(AuthService);
  private readonly api = inject(ApiService);
  private readonly dialog = inject(MatDialog);
  private readonly toast = inject(ToastService);
  private readonly scroller = viewChild<ElementRef<HTMLElement>>('scroller');
  readonly research = !!inject(ActivatedRoute).snapshot.data['research'];
  readonly catalog = signal<Catalog | null>(null);
  readonly conversations = signal<Conversation[]>([]);
  readonly current = signal<Conversation | null>(null);
  readonly busy = signal(false);
  readonly cases = signal<LegalCase[]>([]);
  readonly documents = signal<StoredDocument[]>([]);
  agent = this.research ? 'PESQUISADOR' : 'ASSISTENTE_JURIDICO';
  task = this.research ? 'PESQUISA' : 'CONVERSA';
  question = '';
  client: PickedClient | null = null;
  caseId = '';
  documentIds: string[] = [];

  readonly agentsAvailable = computed(() => (this.catalog()?.agents ?? []).filter((a) => a.available && a.value !== 'PESQUISADOR'));

  constructor() {
    this.api.get<Catalog>('/ai/catalog').subscribe((c) => this.catalog.set(c));
    this.loadConversations();
  }

  render(text: string): string {
    return markdown(text);
  }

  agentLabel(v: string): string {
    return this.catalog()?.agents.find((a) => a.value === v)?.label ?? v;
  }

  taskLabel(v: string): string {
    return this.catalog()?.tasks.find((t) => t.value === v)?.label ?? v;
  }

  agentDescription(): string {
    return this.catalog()?.agents.find((a) => a.value === this.agent)?.description ?? '';
  }

  tasksFor(agent: string): TaskOption[] {
    const allowed = AGENT_TASKS[agent] ?? ['CONVERSA'];
    return (this.catalog()?.tasks ?? []).filter((t) => allowed.includes(t.value));
  }

  taskInfo(): TaskOption | undefined {
    return this.catalog()?.tasks.find((t) => t.value === this.task);
  }

  needsContext(): boolean {
    return !this.research;
  }

  canStart(): boolean {
    const t = this.taskInfo();
    if (this.research || this.task === 'CONVERSA') return !!this.question.trim();
    if (t?.caseRequired && !this.caseId) return false;
    if ((t?.documentsRequired ?? 0) > this.documentIds.length) return false;
    if (this.task === 'EXTRACAO_DATAS' && !this.caseId && !this.documentIds.length) return false;
    return true;
  }

  loadConversations(): void {
    this.api.get<Conversation[]>('/ai/conversations', undefined, true).subscribe((l) =>
      this.conversations.set(l.filter((c) => (c.task === 'PESQUISA') === this.research)));
  }

  newConversation(): void {
    this.current.set(null);
    this.question = '';
  }

  open(id: string): void {
    this.api.get<Conversation>(`/ai/conversations/${id}`).subscribe((c) => { this.current.set(c); this.scrollDown(); });
  }

  pickClient(c: PickedClient | null): void {
    this.client = c;
    this.caseId = '';
    this.documentIds = [];
    this.cases.set([]);
    this.documents.set([]);
    if (!c) return;
    if (this.auth.has('CASES_READ')) {
      this.api.get<CursorPage<LegalCase>>('/cases', { clientId: c.id, size: 50 }, true).subscribe((p) => this.cases.set(p.items));
    }
    this.loadDocuments();
  }

  loadDocuments(): void {
    if (!this.client || !this.auth.has('DOCUMENTS_READ')) return;
    const params = this.caseId ? { caseId: this.caseId, size: 60 } : { clientId: this.client.id, size: 60 };
    this.api.get<CursorPage<StoredDocument>>('/documents', params, true).subscribe((p) => this.documents.set(p.items));
  }

  ask(): void {
    const c = this.current();
    if (this.busy() || (c && !this.question.trim())) return;
    this.busy.set(true);
    const body = c ? { conversationId: c.id, message: this.question.trim() }
      : { agent: this.agent, task: this.task, message: this.question.trim(), caseId: this.caseId || null,
        clientId: this.client?.id ?? null, documentIds: this.documentIds };
    this.api.post<Conversation>('/ai/ask', body).subscribe({
      next: (conv) => {
        this.busy.set(false);
        this.question = '';
        this.current.set(conv);
        this.loadConversations();
        this.scrollDown();
      },
      error: () => this.busy.set(false),
    });
  }

  private scrollDown(): void {
    setTimeout(() => { const el = this.scroller()?.nativeElement; if (el) el.scrollTop = el.scrollHeight; }, 50);
  }

  copy(text: string): void {
    void navigator.clipboard.writeText(text).then(() => this.toast.success('Texto copiado.'));
  }

  review(c: Conversation, i: number): void {
    this.api.post<Conversation>(`/ai/conversations/${c.id}/messages/${i}/review`, {}).subscribe((x) => {
      this.current.set(x);
      this.toast.success('Resposta marcada como revisada.');
    });
  }

  toEditor(c: Conversation, i: number): void {
    const data: ConfirmData = { title: 'Enviar ao Editor Jurídico', confirmLabel: 'Criar rascunho', reasonLabel: 'Título do documento',
      message: 'Cria um rascunho no Editor Jurídico com este texto, marcado como gerado por IA e vinculado ao mesmo cliente/processo.' };
    this.dialog.open(ConfirmDialogComponent, { data }).afterClosed().subscribe((title) => {
      if (title === undefined || title === false) return;
      this.api.post<{ id: string }>(`/ai/conversations/${c.id}/messages/${i}/to-editor`, { title: typeof title === 'string' && title ? title : c.title })
        .subscribe(() => { this.toast.success('Rascunho criado no Editor Jurídico.'); this.open(c.id); });
    });
  }

  createDeadline(c: Conversation, e: Extracted): void {
    this.dialog.open(DeadlineFormDialogComponent, {
      data: { legalCase: { id: c.caseId!, cnjNumber: c.caseNumber, clientName: c.clientName ?? '', court: null }, prefill: { description: e.description ?? '', notes: `Extraído pela IA (confira): “${e.sourceQuote ?? ''}”${e.date ? ' — data ' + e.date : ''}` } },
      width: '760px', maxWidth: '96vw',
    }).afterClosed().subscribe((ok) => { if (ok) this.toast.success('Prazo criado.'); });
  }

  remove(c: Conversation): void {
    const data: ConfirmData = { title: 'Excluir conversa', message: `Excluir "${c.title}"? Os rascunhos já enviados ao Editor continuam lá.`,
      danger: true, confirmLabel: 'Excluir' };
    this.dialog.open(ConfirmDialogComponent, { data }).afterClosed().subscribe((ok) => {
      if (ok) this.api.delete<void>(`/ai/conversations/${c.id}`).subscribe(() => { this.current.set(null); this.loadConversations(); });
    });
  }
}
