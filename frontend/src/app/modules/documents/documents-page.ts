import { Component } from '@angular/core';
import { PageHeaderComponent } from '../../shared/components/ui';
import { DocumentsTableComponent } from './documents-table';

@Component({
  selector: 'app-documents-page',
  imports: [PageHeaderComponent, DocumentsTableComponent],
  template: `
    <div class="page">
      <app-page-header eyebrow="Gestão documental" title="Documentos"
        subtitle="Repositório com versionamento, controle de sigilo e histórico de quem visualizou ou baixou cada arquivo." />
      <app-documents-table />
    </div>
  `,
})
export class DocumentsPageComponent {}
