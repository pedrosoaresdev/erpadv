import { fileExtension, folderTree, formatBytes } from './documents.service';
import { kindOf, normalizeForSearch } from './file-viewer';

describe('Central documental — utilitários', () => {
  it('monta a árvore de pastas em pré-ordem com caminho e profundidade', () => {
    const tree = folderTree([
      { id: 'b', name: 'Trabalhista', parentId: null, color: null, createdAt: '' },
      { id: 'a', name: 'Cível', parentId: null, color: null, createdAt: '' },
      { id: 'a2', name: 'Bancos', parentId: 'a', color: null, createdAt: '' },
      { id: 'a21', name: 'Banco Exemplo', parentId: 'a2', color: null, createdAt: '' },
      { id: 'x', name: 'Órfã', parentId: 'inexistente', color: null, createdAt: '' },
    ]);
    expect(tree.map((f) => f.id)).toEqual(['a', 'a2', 'a21', 'x', 'b']);
    expect(tree.find((f) => f.id === 'a21')!.path).toBe('Cível / Bancos / Banco Exemplo');
    expect(tree.find((f) => f.id === 'a21')!.depth).toBe(2);
    expect(tree.find((f) => f.id === 'x')!.depth).toBe(0);
  });

  it('identifica o tipo de visualização e normaliza a busca no PDF', () => {
    expect(kindOf('application/pdf', 'a.pdf')).toBe('pdf');
    expect(kindOf('image/png', 'a.png')).toBe('image');
    expect(kindOf('text/plain; charset=utf-8', 'a.txt')).toBe('text');
    expect(kindOf('application/vnd.openxmlformats-officedocument.wordprocessingml.document', 'a.docx')).toBe('none');
    expect(normalizeForSearch('Petição  INICIAL\nÇÃO')).toBe('peticao inicial cao');
  });

  it('formata tamanhos e extensões', () => {
    expect(formatBytes(512)).toBe('512 B');
    expect(formatBytes(2048)).toBe('2 KB');
    expect(formatBytes(25 * 1024 * 1024)).toBe('25,0 MB');
    expect(fileExtension('Contrato.Final.DOCX')).toBe('docx');
    expect(fileExtension('sem-extensao')).toBe('');
  });
});
