package br.com.erpadv.firebase;

import br.com.erpadv.exceptions.BusinessException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Fase 2 — formatos da central documental validados pela assinatura binária (não pela extensão declarada). */
class FileInspectorFormatsTest {

    private static final byte[] OLE = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1, 0, 0, 0, 0};

    private static byte[] zip(String entry) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            z.putNextEntry(new ZipEntry("[Content_Types].xml"));
            z.write("<Types/>".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
            z.putNextEntry(new ZipEntry(entry));
            z.write("<x/>".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        return out.toByteArray();
    }

    @Test
    void officeOpenXmlIdentificadoPeloConteudoDoPacote() throws Exception {
        assertThat(FileInspector.detect(zip("word/document.xml"), "a.docx")).isEqualTo(FileInspector.FileKind.DOCX);
        assertThat(FileInspector.detect(zip("xl/workbook.xml"), "planilha.xlsx")).isEqualTo(FileInspector.FileKind.XLSX);
        assertThat(FileInspector.detect(zip("ppt/presentation.xml"), "slides.pptx")).isEqualTo(FileInspector.FileKind.PPTX);
        // ZIP comum (não Office) é recusado mesmo com extensão .docx
        assertThat(FileInspector.detect(zip("payload/run.sh"), "falso.docx")).isNull();
    }

    @Test
    void formatosLegadosOleDesempatadosPelaExtensao() {
        assertThat(FileInspector.detect(OLE, "peticao.doc")).isEqualTo(FileInspector.FileKind.DOC);
        assertThat(FileInspector.detect(OLE, "calculo.XLS")).isEqualTo(FileInspector.FileKind.XLS);
        assertThat(FileInspector.detect(OLE, "audiencia.ppt")).isEqualTo(FileInspector.FileKind.PPT);
        assertThat(FileInspector.detect(OLE, "macro.exe")).isNull();
    }

    @Test
    void textoPuroSomenteUtf8ValidoSemBytesNulos() {
        assertThat(FileInspector.detect("Anotações da reunião".getBytes(StandardCharsets.UTF_8), "notas.txt"))
                .isEqualTo(FileInspector.FileKind.TXT);
        assertThat(FileInspector.detect(new byte[]{'a', 0, 'b'}, "binario.txt")).isNull();
        assertThat(FileInspector.detect(new byte[]{(byte) 0xC3, (byte) 0x28}, "invalido.txt")).isNull();
        // texto com extensão desconhecida não é aceito como TXT
        assertThat(FileInspector.detect("echo hi".getBytes(StandardCharsets.UTF_8), "script.sh")).isNull();
    }

    @Test
    void executavelDisfarcadoRecusadoNaInspecao() {
        assertThatThrownBy(() -> FileInspector.inspect(new byte[]{'M', 'Z', 1, 2, 3}, "contrato.pdf", 1024,
                EnumSet.allOf(FileInspector.FileKind.class)))
                .isInstanceOf(BusinessException.class);
        FileInspector.Inspection ok = FileInspector.inspect("%PDF-1.4\n".getBytes(StandardCharsets.US_ASCII), "../../etc/peça.pdf", 1024,
                EnumSet.allOf(FileInspector.FileKind.class));
        assertThat(ok.kind()).isEqualTo(FileInspector.FileKind.PDF);
        assertThat(ok.safeName()).doesNotContain("/").doesNotContain("..");
    }
}
