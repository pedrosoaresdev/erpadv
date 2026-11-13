package br.com.erpadv.services.platform;

import br.com.erpadv.audit.AuditService;
import br.com.erpadv.exceptions.BusinessException;
import br.com.erpadv.exceptions.NotFoundException;
import br.com.erpadv.models.Office;
import br.com.erpadv.repositories.OfficeRepository;
import br.com.erpadv.security.AuthenticatedUser;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Atalhos para os sistemas dos tribunais (PJe e outros). Os tribunais não permitem exibir o PJe dentro de outro site
 * (política de segurança e cookies de sessão), então os sistemas abrem em janela própria, com o login do próprio tribunal
 * (certificado digital/PJe Office) — o ERPAdv não acessa, não intermedeia e não guarda credenciais judiciais.
 */
@Service
public class SystemLinksService {

    public record LinkInput(String id, String name, String url, String court, String description) {
    }

    private final OfficeRepository offices;
    private final AuditService audit;

    public SystemLinksService(OfficeRepository offices, AuditService audit) {
        this.offices = offices;
        this.audit = audit;
    }

    /** Lista padrão: endereços oficiais conferidos (responderam no endereço indicado). */
    public static List<Office.SystemLink> defaults() {
        List<Office.SystemLink> l = new ArrayList<>();
        l.add(link("pje-tjpe-1g", "PJe — TJPE (1º grau)", "https://pje.cloud.tjpe.jus.br/1g/login.seam", "TJPE",
                "Processo Judicial Eletrônico do Tribunal de Justiça de Pernambuco"));
        l.add(link("pje-trf5", "PJe — TRF5", "https://pje.trf5.jus.br/pje/login.seam", "TRF5", "Justiça Federal da 5ª Região"));
        l.add(link("pje-trt6", "PJe — TRT6 (1º grau)", "https://pje.trt6.jus.br/primeirograu/login.seam", "TRT6",
                "Justiça do Trabalho em Pernambuco"));
        l.add(link("jus-br", "Jus.br — Portal de Serviços do Judiciário", "https://www.jus.br/", null,
                "Acesso unificado do CNJ a serviços e processos de vários tribunais"));
        l.add(link("comunica-pje", "Comunica PJe (DJEN)", "https://comunica.pje.jus.br/", null,
                "Consulta às comunicações processuais publicadas no Diário de Justiça Eletrônico Nacional"));
        return l;
    }

    private static Office.SystemLink link(String id, String name, String url, String court, String description) {
        Office.SystemLink s = new Office.SystemLink();
        s.setId(id);
        s.setName(name);
        s.setUrl(url);
        s.setCourt(court);
        s.setDescription(description);
        return s;
    }

    public List<Office.SystemLink> list(AuthenticatedUser user) {
        Office o = offices.findById(user.requireOffice()).orElseThrow(() -> new NotFoundException("Escritório"));
        return o.getSystemLinks() == null ? defaults() : o.getSystemLinks();
    }

    public List<Office.SystemLink> save(AuthenticatedUser user, List<LinkInput> input) {
        Office o = offices.findById(user.requireOffice()).orElseThrow(() -> new NotFoundException("Escritório"));
        if (input == null || input.size() > 40) {
            throw new BusinessException("INVALID_SYSTEMS", "Cadastre até 40 sistemas.");
        }
        List<Office.SystemLink> out = new ArrayList<>();
        Set<String> urls = new HashSet<>();
        for (LinkInput in : input) {
            String name = clean(in.name(), 80);
            String url = clean(in.url(), 500);
            if (name == null || url == null) {
                throw new BusinessException("INVALID_SYSTEMS", "Informe o nome e o endereço de cada sistema.");
            }
            URI uri;
            try {
                uri = URI.create(url);
            } catch (IllegalArgumentException e) {
                throw new BusinessException("INVALID_URL", "Endereço inválido: " + url);
            }
            // só HTTPS e sem credenciais embutidas no endereço
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
                throw new BusinessException("INVALID_URL", "Use um endereço https:// válido para " + name + ".");
            }
            if (!urls.add(url.toLowerCase(Locale.ROOT))) {
                continue;
            }
            Office.SystemLink s = new Office.SystemLink();
            s.setId(in.id() == null || in.id().isBlank() ? UUID.randomUUID().toString() : clean(in.id(), 64));
            s.setName(name);
            s.setUrl(url);
            String court = clean(in.court(), 20);
            s.setCourt(court == null ? null : court.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9-]", ""));
            s.setDescription(clean(in.description(), 200));
            out.add(s);
        }
        o.setSystemLinks(out);
        o.setUpdatedAt(Instant.now());
        o.setUpdatedBy(user.uid());
        offices.save(o);
        audit.record(user, "SYSTEM_LINKS_UPDATED", "office", o.getId(), out.size() + " sistema(s) de tribunais configurado(s)");
        return out;
    }

    private static String clean(String s, int max) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String t = s.strip();
        return t.length() > max ? t.substring(0, max) : t;
    }
}
