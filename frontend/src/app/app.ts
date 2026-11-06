import { Component, inject } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { ThemeService } from './core/services/theme.service';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet],
  template: `<router-outlet />`,
})
export class App {
  // Instancia o serviço de tema logo na inicialização (aplica claro/escuro antes da primeira tela).
  protected readonly theme = inject(ThemeService);
}
