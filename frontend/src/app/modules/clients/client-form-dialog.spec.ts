import { TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { Client } from '../../core/models/models';
import { ClientFormDialogComponent } from './client-form-dialog';
import { ClientsService } from './clients.service';
import { provideRouter } from '@angular/router';
import { ApiService } from '../../core/api/api.service';

describe('ClientFormDialogComponent', () => {
  const service = jasmine.createSpyObj<ClientsService>('ClientsService', ['create', 'update']);
  const ref = jasmine.createSpyObj<MatDialogRef<ClientFormDialogComponent>>('MatDialogRef', ['close']);
  const api = jasmine.createSpyObj<ApiService>('ApiService', ['get']);
  api.get.and.returnValue(of([]) as never);

  function setup(data: Client | null) {
    TestBed.configureTestingModule({
      imports: [ClientFormDialogComponent],
      providers: [
        provideNoopAnimations(),
        { provide: MAT_DIALOG_DATA, useValue: data },
        { provide: MatDialogRef, useValue: ref },
        { provide: ClientsService, useValue: service },
        { provide: ApiService, useValue: api },
        provideRouter([]),
      ],
    });
    const fixture = TestBed.createComponent(ClientFormDialogComponent);
    fixture.detectChanges();
    return fixture.componentInstance;
  }

  beforeEach(() => {
    service.create.calls.reset();
    service.update.calls.reset();
  });

  it('exige nome e CPF válido no cadastro', () => {
    const c = setup(null);
    c.save();
    expect(service.create).not.toHaveBeenCalled();
    c.form.patchValue({ name: 'Maria', document: '529.982.247-24' });
    expect(c.form.controls.document.hasError('cpf')).toBeTrue();
  });

  it('pessoa jurídica exige CNPJ', () => {
    const c = setup(null);
    c.form.patchValue({ type: 'PJ', name: 'Empresa', document: '529.982.247-25' });
    expect(c.form.controls.document.hasError('cnpj')).toBeTrue();
  });

  it('envia o payload normalizado', () => {
    service.create.and.returnValue(of({ id: 'c1' } as Client));
    const c = setup(null);
    c.form.patchValue({ name: '  Maria  ', document: '529.982.247-25', email: 'maria@x.test', notes: '' });
    c.save();
    const body = service.create.calls.mostRecent().args[0];
    expect(body.name).toBe('Maria');
    expect(body.notes).toBeNull();
    expect(body.address).toBeNull();
    expect(ref.close).toHaveBeenCalled();
  });

  it('na edição sem acesso a dados sensíveis não envia CPF nem RG', () => {
    service.update.and.returnValue(of({ id: 'c1' } as Client));
    const c = setup({
      id: 'c1', type: 'PF', name: 'Maria', document: '***.***.247-25', documentMasked: '***.***.247-25',
      rg: '***', sensitiveVisible: false, status: 'ACTIVE', defaulting: false,
    } as Client);
    c.save();
    const body = service.update.calls.mostRecent().args[1];
    expect(body.document).toBeNull();
    expect(body.rg).toBeNull();
  });

  it('pessoa jurídica envia inscrições, representante, contatos e origem', () => {
    service.create.and.returnValue(of({ id: 'c2' } as Client));
    const c = setup(null);
    c.form.patchValue({ type: 'PJ', name: 'Construtora Horizonte', document: '11.222.333/0001-81', stateRegistration: '123',
      legalRepresentative: 'Carlos', source: 'INDICACAO', nationality: 'Brasileira' });
    c.addContact();
    c.contacts.at(0).patchValue({ name: 'Paula', role: 'Financeiro', email: 'paula@x.test' });
    c.save();
    const body = service.create.calls.mostRecent().args[0];
    expect(body.stateRegistration).toBe('123');
    expect(body.legalRepresentative).toBe('Carlos');
    expect(body.nationality).toBeNull();
    expect(body.source).toBe('INDICACAO');
    expect(body.contacts).toEqual([{ name: 'Paula', role: 'Financeiro', phone: null, email: 'paula@x.test' }]);
  });

  it('verifica possíveis duplicidades pelo telefone', () => {
    const c = setup(null);
    c.form.patchValue({ phone: '(81) 3333-4444' });
    c.checkDuplicates();
    expect(api.get).toHaveBeenCalledWith('/clients/duplicates', jasmine.objectContaining({ phone: '(81) 3333-4444' }), true);
  });
});
