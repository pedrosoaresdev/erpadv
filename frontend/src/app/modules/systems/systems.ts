import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ApiService } from '../../core/api/api.service';
import { AuthService } from '../../core/auth/auth.service';
import { ToastService } from '../../core/services/toast.service';
import { EmptyStateComponent, PageHeaderComponent } from '../../shared/components/ui';

export interface SystemLink { id: string; name: string; url: string; court: string | null; description: string | null }

/**
 * Abre o sistema do tribunal numa janela própria ao lado do ERPAdv. Os tribunais não permitem exibir o PJe dentro de outro
 * site; a janela usa o login do próprio tribunal (certificado digital/PJe Office).
 */
export function openSystem(url: string): void {
  const w = Math.round(Math.max(900, window.screen.availWidth * 0.62));
  const h = Math.round(window.screen.availHeight * 0.92);
  const left = Math.max(0, window.screen.availWidth - w);
  const win = window.open(url, 'erpadv-sistema-judicial', `popup=yes,width=${w},height=${h},left=${left},top=0,noopener=no`);
  if (!win) {
    // bloqueador de pop-up: abre em nova aba
    window.open(url, '_blank', 'noopener');
    return;
  }
  win.opener = null;
  win.focus();
}

/** Sistemas dos tribunais (PJe e outros) com acesso a partir do ERPAdv. */
@Component({
  selector: 'app-systems-page',
  imports: [FormsModule, MatButtonModule, MatIconModule, MatFormFieldModule, MatInputModule, MatTooltipModule, PageHeaderComponent, EmptyStateComponent],
  template: `
    <div class="page">
      <app-page-header eyebrow="Sistemas" title="PJe e sistemas dos tribunais"
        subtitle="Acesse o PJe e os demais sistemas judiciais a partir do ERPAdv, com o login do próprio tribunal.">
        @if (canEdit() && !editing()) { <button mat-stroked-button (click)="startEdit()"><mat-icon>edit</mat-icon>Editar lista</button> }
      </app-page-header>

      <div class="card notice">
        <mat-icon>info</mat-icon>
        <p>Por segurança, os tribunais não permitem que o PJe seja exibido dentro de outros sites. Ele abre numa <strong>janela própria
          ao lado do ERPAdv</strong> e o login com certificado digital (PJe Office) funciona normalmente. O ERPAdv não acessa o PJe por você
          e não guarda senhas ou certificados.</p>
      </div>

      @if (!editing()) {
        @if (links().length === 0) {
          <app-empty-state icon="account_balance" title="Nenhum sistema cadastrado" text="O administrador pode cadastrar os sistemas dos tribunais em que o escritório atua." />
        } @else {
          <div class="grid">
            @for (l of links(); track l.id) {
              <section class="card sys">
                <div class="ic"><mat-icon>{{ l.name.toLowerCase().includes('pje') ? 'gavel' : 'account_balance' }}</mat-icon></div>
                <div class="body">
                  <strong>{{ l.name }}</strong>
                  @if (l.court) { <span class="badge">{{ l.court }}</span> }
                  @if (l.description) { <p class="muted small">{{ l.description }}</p> }
                  <span class="muted tiny url">{{ host(l.url) }}</span>
                </div>
                <div class="actions">
                  <button mat-flat-button (click)="open(l)"><mat-icon>open_in_new</mat-icon>Abrir</button>
                  <a mat-icon-button [href]="l.url" target="_blank" rel="noopener noreferrer" matTooltip="Abrir em nova aba" aria-label="Abrir em nova aba"><mat-icon>tab</mat-icon></a>
                </div>
              </section>
            }
          </div>
        }
      } @else {
        <section class="card edit">
          <p class="muted small">Use o endereço oficial de login de cada sistema (https). Preencha a sigla do tribunal (ex.: TJPE, TRF5) para o botão
            “Abrir no PJe” aparecer nos processos desse tribunal.</p>
          @for (l of draft; track $index; let i = $index) {
            <div class="row">
              <mat-form-field class="name"><mat-label>Nome</mat-label><input matInput [(ngModel)]="l.name" maxlength="80" /></mat-form-field>
              <mat-form-field class="court"><mat-label>Tribunal</mat-label><input matInput [(ngModel)]="l.court" maxlength="20" placeholder="TJPE" /></mat-form-field>
              <mat-form-field class="url"><mat-label>Endereço (https://…)</mat-label><input matInput [(ngModel)]="l.url" maxlength="500" /></mat-form-field>
              <mat-form-field class="desc"><mat-label>Descrição (opcional)</mat-label><input matInput [(ngModel)]="l.description" maxlength="200" /></mat-form-field>
              <button mat-icon-button (click)="draft.splice(i, 1)" aria-label="Remover sistema"><mat-icon>delete</mat-icon></button>
            </div>
          }
          <div class="bar">
            <button mat-button (click)="draft.push({ id: '', name: '', url: 'https://', court: null, description: null })"><mat-icon>add</mat-icon>Adicionar sistema</button>
            <span class="spacer"></span>
            <button mat-button (click)="editing.set(false)">Cancelar</button>
            <button mat-flat-button (click)="save()" [disabled]="saving()"><mat-icon>save</mat-icon>Salvar</button>
          </div>
        </section>
      }
    </div>
  `,
  styles: `
    .notice { display: flex; gap: 12px; align-items: flex-start; padding: 14px 18px; margin-bottom: 16px; }
    .notice mat-icon { color: var(--app-gold, #b8955a); flex: none; }
    .notice p { margin: 0; line-height: 1.5; }
    .grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(320px, 1fr)); gap: 16px; }
    .sys { display: flex; gap: 14px; align-items: center; padding: 16px 18px; }
    .ic { width: 44px; height: 44px; border-radius: 12px; display: grid; place-items: center; background: var(--app-surface-2); flex: none; }
    .body { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px; }
    .body p { margin: 2px 0 0; }
    .url { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .actions { display: flex; align-items: center; gap: 4px; flex: none; }
    .small { font-size: 12px; }
    .tiny { font-size: 11px; }
    .badge { align-self: flex-start; }
    .edit { padding: 16px 20px; }
    .row { display: grid; grid-template-columns: 1.2fr 110px 1.6fr 1.2fr 40px; gap: 8px; align-items: center; }
    .bar { display: flex; gap: 8px; align-items: center; margin-top: 8px; flex-wrap: wrap; }
    @media (max-width: 900px) { .row { grid-template-columns: minmax(0, 1fr); border-bottom: 1px solid var(--app-border); padding-bottom: 8px; } .sys { flex-wrap: wrap; } }
  `,
})
export class SystemsPageComponent {
  private readonly api = inject(ApiService);
  private readonly auth = inject(AuthService);
  private readonly toast = inject(ToastService);
  readonly links = signal<SystemLink[]>([]);
  readonly editing = signal(false);
  readonly saving = signal(false);
  readonly canEdit = computed(() => this.auth.has('SETTINGS_MANAGE'));
  draft: SystemLink[] = [];

  constructor() {
    this.api.get<SystemLink[]>('/office/systems').subscribe((l) => this.links.set(l));
  }

  host(url: string): string {
    try { return new URL(url).host; } catch { return url; }
  }

  open(l: SystemLink): void {
    openSystem(l.url);
  }

  startEdit(): void {
    this.draft = this.links().map((l) => ({ ...l }));
    this.editing.set(true);
  }

  save(): void {
    this.saving.set(true);
    this.api.put<SystemLink[]>('/office/systems', this.draft.filter((l) => l.name.trim() || l.url.trim() !== 'https://')).subscribe({
      next: (l) => { this.links.set(l); this.saving.set(false); this.editing.set(false); this.toast.success('Lista de sistemas salva.'); },
      error: () => this.saving.set(false),
    });
  }
}
