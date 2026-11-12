import { Component, inject, signal } from '@angular/core';
import { AbstractControl, FormBuilder, ReactiveFormsModule, ValidationErrors, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { AuthService, firebaseAuthMessage } from '../../core/auth/auth.service';
import { ToastService } from '../../core/services/toast.service';

export const AUTH_STYLES = `
  .auth-card { padding: 36px 32px; }
  h2 { font-size: 24px; font-weight: 700; letter-spacing: -.3px; }
  .sub { margin: 6px 0 24px; color: var(--app-text-muted); line-height: 1.5; }
  form { display: flex; flex-direction: column; gap: 4px; }
  .submit { height: 46px; margin-top: 8px; font-weight: 600; }
  .links { display: flex; justify-content: space-between; margin-top: 18px; font-size: 13px; gap: 12px; flex-wrap: wrap; }
  .error { background: var(--app-danger-bg); color: var(--app-danger); border-radius: 10px; padding: 10px 14px; margin-bottom: 12px; font-size: 13px; }
  .ok { background: var(--app-success-bg); color: var(--app-success); border-radius: 10px; padding: 12px 14px; margin-bottom: 12px; line-height: 1.45; }
  mat-progress-bar { margin: -36px -32px 32px; width: calc(100% + 64px); border-radius: 12px 12px 0 0; }
`;

const AUTH_IMPORTS = [
  ReactiveFormsModule, RouterLink, MatFormFieldModule, MatInputModule, MatButtonModule, MatIconModule, MatProgressBarModule,
];

// ======================================================================= login
@Component({
  selector: 'app-login',
  imports: AUTH_IMPORTS,
  template: `
    <div class="card auth-card">
      @if (loading()) { <mat-progress-bar mode="indeterminate" /> }
      <h2>Acesse sua conta</h2>
      <p class="sub">Entre com o e-mail e a senha cadastrados no escritório.</p>
      @if (error()) { <div class="error" role="alert">{{ error() }}</div> }
      <form [formGroup]="form" (ngSubmit)="submit()">
        <mat-form-field appearance="outline">
          <mat-label>E-mail</mat-label>
          <input matInput type="email" formControlName="email" autocomplete="username" />
          <mat-icon matPrefix>mail</mat-icon>
          @if (form.controls.email.invalid) { <mat-error>Informe um e-mail válido</mat-error> }
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Senha</mat-label>
          <input matInput [type]="showPassword() ? 'text' : 'password'" formControlName="password" autocomplete="current-password" />
          <mat-icon matPrefix>lock</mat-icon>
          <button mat-icon-button matSuffix type="button" (click)="showPassword.set(!showPassword())" [attr.aria-label]="showPassword() ? 'Ocultar senha' : 'Mostrar senha'">
            <mat-icon>{{ showPassword() ? 'visibility_off' : 'visibility' }}</mat-icon>
          </button>
          @if (form.controls.password.invalid) { <mat-error>Informe a senha</mat-error> }
        </mat-form-field>
        <button mat-flat-button class="submit" type="submit" [disabled]="loading()">Entrar</button>
      </form>
      <div class="links">
        <a routerLink="/recuperar-senha">Esqueci minha senha</a>
        <a routerLink="/cadastro">Criar conta</a>
      </div>
    </div>
  `,
  styles: AUTH_STYLES,
})
export class LoginComponent {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  readonly showPassword = signal(false);
  readonly form = inject(FormBuilder).nonNullable.group({
    email: ['', [Validators.required, Validators.email]],
    password: ['', Validators.required],
  });

  async submit(): Promise<void> {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.loading.set(true);
    this.error.set(null);
    try {
      const me = await this.auth.login(this.form.getRawValue().email, this.form.getRawValue().password);
      const back = this.route.snapshot.queryParamMap.get('voltar');
      const home = this.auth.homePath(me);
      await this.router.navigateByUrl(home === '/dashboard' && back?.startsWith('/') ? back : home);
    } catch (e) {
      this.error.set((e as { detail?: string }).detail ?? firebaseAuthMessage(e));
    } finally {
      this.loading.set(false);
    }
  }
}

// ======================================================================= cadastro
function passwordsMatch(group: AbstractControl): ValidationErrors | null {
  return group.get('password')?.value === group.get('confirm')?.value ? null : { mismatch: true };
}

@Component({
  selector: 'app-register',
  imports: AUTH_IMPORTS,
  template: `
    <div class="card auth-card">
      @if (loading()) { <mat-progress-bar mode="indeterminate" /> }
      <h2>Crie a conta do escritório</h2>
      <p class="sub">Você será o sócio administrador e poderá convidar a equipe em seguida.</p>
      @if (error()) { <div class="error" role="alert">{{ error() }}</div> }
      <form [formGroup]="form" (ngSubmit)="submit()">
        <mat-form-field appearance="outline">
          <mat-label>Seu nome completo</mat-label>
          <input matInput formControlName="name" autocomplete="name" />
          @if (form.controls.name.invalid) { <mat-error>Informe seu nome</mat-error> }
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>E-mail profissional</mat-label>
          <input matInput type="email" formControlName="email" autocomplete="email" />
          @if (form.controls.email.invalid) { <mat-error>Informe um e-mail válido</mat-error> }
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Senha</mat-label>
          <input matInput type="password" formControlName="password" autocomplete="new-password" />
          <mat-hint>Mínimo de 8 caracteres, com letras e números</mat-hint>
          @if (form.controls.password.invalid) { <mat-error>Mínimo de 8 caracteres, com letras e números</mat-error> }
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Confirme a senha</mat-label>
          <input matInput type="password" formControlName="confirm" autocomplete="new-password" />
        </mat-form-field>
        @if (form.hasError('mismatch') && form.controls.confirm.touched) { <div class="error">As senhas não conferem.</div> }
        <button mat-flat-button class="submit" type="submit" [disabled]="loading()">Criar conta</button>
      </form>
      <div class="links"><a routerLink="/login">Já tenho conta</a></div>
    </div>
  `,
  styles: AUTH_STYLES,
})
export class RegisterComponent {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  readonly form = inject(FormBuilder).nonNullable.group(
    {
      name: ['', [Validators.required, Validators.maxLength(120)]],
      email: ['', [Validators.required, Validators.email]],
      password: ['', [Validators.required, Validators.minLength(8), Validators.pattern(/^(?=.*[A-Za-z])(?=.*\d).+$/)]],
      confirm: ['', Validators.required],
    },
    { validators: passwordsMatch },
  );

  async submit(): Promise<void> {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.loading.set(true);
    this.error.set(null);
    const { name, email, password } = this.form.getRawValue();
    try {
      await this.auth.register(name, email, password);
      await this.router.navigateByUrl(this.auth.homePath());
    } catch (e) {
      this.error.set(firebaseAuthMessage(e));
    } finally {
      this.loading.set(false);
    }
  }
}

// ======================================================================= recuperar senha
@Component({
  selector: 'app-forgot-password',
  imports: AUTH_IMPORTS,
  template: `
    <div class="card auth-card">
      @if (loading()) { <mat-progress-bar mode="indeterminate" /> }
      <h2>Recuperar acesso</h2>
      <p class="sub">Enviaremos um link para você definir uma nova senha.</p>
      @if (sent()) {
        <div class="ok">Se houver uma conta para este e-mail, o link de redefinição foi enviado. Verifique também a caixa de spam.</div>
      }
      <form [formGroup]="form" (ngSubmit)="submit()">
        <mat-form-field appearance="outline">
          <mat-label>E-mail</mat-label>
          <input matInput type="email" formControlName="email" autocomplete="email" />
          @if (form.controls.email.invalid) { <mat-error>Informe um e-mail válido</mat-error> }
        </mat-form-field>
        <button mat-flat-button class="submit" type="submit" [disabled]="loading()">Enviar link</button>
      </form>
      <div class="links"><a routerLink="/login">Voltar ao login</a></div>
    </div>
  `,
  styles: AUTH_STYLES,
})
export class ForgotPasswordComponent {
  private readonly auth = inject(AuthService);
  readonly loading = signal(false);
  readonly sent = signal(false);
  readonly form = inject(FormBuilder).nonNullable.group({ email: ['', [Validators.required, Validators.email]] });

  async submit(): Promise<void> {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.loading.set(true);
    try {
      await this.auth.resetPassword(this.form.getRawValue().email);
    } catch {
      // Resposta idêntica para e-mails existentes ou não (evita enumeração de contas).
    } finally {
      this.sent.set(true);
      this.loading.set(false);
    }
  }
}

// ======================================================================= verificação de e-mail
@Component({
  selector: 'app-verify-email',
  imports: [MatButtonModule, MatProgressBarModule],
  template: `
    <div class="card auth-card">
      @if (loading()) { <mat-progress-bar mode="indeterminate" /> }
      <h2>Confirme seu e-mail</h2>
      <p class="sub">Enviamos um link de confirmação para <strong>{{ auth.user()?.email }}</strong>. Abra o link e depois clique em “Já confirmei”.</p>
      @if (error()) { <div class="error">{{ error() }}</div> }
      <button mat-flat-button class="submit" style="width:100%" (click)="check()" [disabled]="loading()">Já confirmei</button>
      <div class="links">
        <a href="" (click)="$event.preventDefault(); resend()">Reenviar e-mail</a>
        <a href="" (click)="$event.preventDefault(); auth.logout()">Sair</a>
      </div>
    </div>
  `,
  styles: AUTH_STYLES,
})
export class VerifyEmailComponent {
  readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly toast = inject(ToastService);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);

  async check(): Promise<void> {
    this.loading.set(true);
    this.error.set(null);
    try {
      const me = await this.auth.refresh();
      if (me.emailVerificationRequired) {
        this.error.set('Ainda não identificamos a confirmação. Verifique o link enviado.');
      } else {
        await this.router.navigateByUrl(this.auth.homePath(me));
      }
    } finally {
      this.loading.set(false);
    }
  }

  async resend(): Promise<void> {
    try {
      await this.auth.resendVerification();
      this.toast.success('E-mail de confirmação reenviado.');
    } catch (e) {
      this.toast.error(firebaseAuthMessage(e));
    }
  }
}
