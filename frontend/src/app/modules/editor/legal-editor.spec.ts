import { escapeHtml, plainText, sanitizeEditorHtml } from './legal-editor.service';

describe('Editor Jurídico — saneamento', () => {
  it('remove scripts, eventos, links, imagens e estilos arbitrários', () => {
    const html = sanitizeEditorHtml(
      '<p style="text-align: center; color: red" onclick="x()">Olá <a href="javascript:alert(1)">link</a>'
      + '<img src=x onerror="alert(1)"><script>alert(1)</script><font face="Arial"><b>negrito</b></font></p>'
      + '<hr class="page-break"><span class="field">[cliente.nome]</span><iframe src="https://x"></iframe>',
    );
    expect(html).toContain('style="text-align: center;"');
    expect(html).not.toContain('color');
    expect(html).not.toContain('onclick');
    expect(html).not.toContain('<a');
    expect(html).not.toContain('<img');
    expect(html).not.toContain('script');
    expect(html).not.toContain('iframe');
    expect(html).not.toContain('font');
    expect(html).toContain('link');
    expect(html).toContain('<b>negrito</b>');
    expect(html).toContain('<hr class="page-break">');
    expect(html).toContain('<span class="field">[cliente.nome]</span>');
  });

  it('preserva recuo, tabelas e listas do Word ao colar', () => {
    const html = sanitizeEditorHtml('<p class="MsoNormal" style="margin-left: 48px; mso-x: 1">Recuo</p>'
      + '<table border="1"><tr><td colspan="2" style="width: 10px">A</td></tr></table><ol><li>um</li></ol>');
    expect(html).toContain('style="margin-left: 48px;"');
    expect(html).not.toContain('Mso');
    expect(html).toContain('colspan="2"');
    expect(html).toContain('<li>um</li>');
  });

  it('escapa valores e extrai texto', () => {
    expect(escapeHtml('<b>"A&B"</b>')).toBe('&lt;b&gt;&quot;A&amp;B&quot;&lt;/b&gt;');
    expect(plainText('<p>Olá <strong>mundo</strong></p>')).toBe('Olá mundo');
  });
});
