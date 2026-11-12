import { markdown } from './ai-page';

describe('markdown da IA', () => {
  it('escapa HTML e não cria scripts nem atributos', () => {
    const html = markdown('<script>alert(1)</script> <img src=x onerror=alert(1)> **ok**');
    expect(html).not.toContain('<script');
    expect(html).not.toContain('<img');
    expect(html).toContain('&lt;script&gt;');
    expect(html).toContain('<strong>ok</strong>');
  });

  it('converte títulos, listas e links http(s) com rel seguro', () => {
    const html = markdown('## Resumo\n- item 1\n- item 2\n\nVeja https://www.planalto.gov.br/lei e [STJ](https://www.stj.jus.br)');
    expect(html).toContain('<h4>Resumo</h4>');
    expect(html).toContain('<ul><li>item 1</li><li>item 2</li></ul>');
    expect(html).toContain('href="https://www.planalto.gov.br/lei"');
    expect(html).toContain('rel="noopener noreferrer"');
    expect(html).toContain('>STJ</a>');
  });

  it('não transforma javascript: em link', () => {
    expect(markdown('[x](javascript:alert(1))')).not.toContain('href=');
  });
});
