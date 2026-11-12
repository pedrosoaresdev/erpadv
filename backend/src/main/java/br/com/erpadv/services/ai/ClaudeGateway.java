package br.com.erpadv.services.ai;

import br.com.erpadv.exceptions.BusinessException;
import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonSchemaLocalValidation;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.BadRequestException;
import com.anthropic.errors.PermissionDeniedException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.TextCitation;
import com.anthropic.models.messages.WebSearchTool20260209;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Acesso à API de Mensagens da Anthropic pelo SDK oficial Java. A chave nunca sai do backend; os clientes são reaproveitados
 * por chave (o SDK mantém o pool de conexões).
 */
@Component
public class ClaudeGateway {

    private static final Logger log = LoggerFactory.getLogger(ClaudeGateway.class);
    /** Sites oficiais aceitos na pesquisa jurídica (subdomínios incluídos: tribunais, Planalto, DOU, Câmara, Senado, MP). */
    public static final List<String> OFFICIAL_DOMAINS = List.of("jus.br", "gov.br", "leg.br", "mp.br");
    private static final int MAX_CONTINUATIONS = 3;

    public record Citation(String url, String title, String citedText) {
    }

    public record Result(String text, List<Citation> citations, String stopReason, String refusalCategory, long inputTokens,
                         long outputTokens, String model) {
    }

    public record Structured<T>(T value, String stopReason, long inputTokens, long outputTokens, String model) {
    }

    private final String model;
    private final String baseUrl;
    private final String fallbacks;
    private final Map<String, AnthropicClient> clients = new ConcurrentHashMap<>();

    public ClaudeGateway(@Value("${app.ai.model:claude-opus-5-5}") String model,
                         @Value("${app.ai.base-url:}") String baseUrl,
                         @Value("${app.ai.fallbacks:default}") String fallbacks) {
        this.model = model;
        this.baseUrl = baseUrl;
        this.fallbacks = fallbacks;
    }

    public String model() {
        return model;
    }

    private AnthropicClient client(String apiKey) {
        return clients.computeIfAbsent(sha(apiKey), k -> {
            AnthropicOkHttpClient.Builder b = AnthropicOkHttpClient.builder().apiKey(apiKey).timeout(Duration.ofMinutes(10)).maxRetries(2);
            if (baseUrl != null && !baseUrl.isBlank()) {
                b.baseUrl(baseUrl);
            }
            return b.build();
        });
    }

    private MessageCreateParams.Builder base(String system, OutputConfig.Effort effort) {
        MessageCreateParams.Builder b = MessageCreateParams.builder()
                .model(model)
                .maxTokens(16000L)
                .system(system)
                .outputConfig(OutputConfig.builder().effort(effort).build());
        applyFallbacks(b);
        return b;
    }

    /**
     * Se o modelo principal recusar por política de segurança, a própria API repete o pedido em outro modelo dentro da mesma
     * chamada (fallbacks "default"). Desligável com AI_FALLBACKS=off.
     */
    private void applyFallbacks(MessageCreateParams.Builder b) {
        if (fallbacks != null && !fallbacks.isBlank() && !"off".equalsIgnoreCase(fallbacks)) {
            b.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01");
            b.putAdditionalBodyProperty("fallbacks", JsonValue.from(fallbacks));
        }
    }

    /**
     * Conversa com contexto (texto e, se houver, PDFs digitalizados). Com {@code webSearch}, usa a busca na web restrita a sites
     * oficiais e devolve as citações. Continua automaticamente quando a API pausa a resposta (pause_turn).
     */
    public Result complete(String apiKey, String system, List<MessageParam> history, List<ContentBlockParam> userContent,
                           OutputConfig.Effort effort, boolean webSearch) {
        MessageCreateParams.Builder b = base(system, effort);
        history.forEach(b::addMessage);
        b.addUserMessageOfContentBlockParams(userContent);
        if (webSearch) {
            b.addTool(WebSearchTool20260209.builder().maxUses(8L).allowedDomains(OFFICIAL_DOMAINS).build());
        }
        StringBuilder text = new StringBuilder();
        List<Citation> citations = new ArrayList<>();
        long in = 0;
        long out = 0;
        Message msg = null;
        for (int i = 0; i <= MAX_CONTINUATIONS; i++) {
            msg = call(() -> client(apiKey).messages().create(b.build()));
            in += msg.usage().inputTokens();
            out += msg.usage().outputTokens();
            collect(msg, text, citations);
            if (msg.stopReason().filter(StopReason.PAUSE_TURN::equals).isEmpty()) {
                break;
            }
            // a resposta pausada volta como turno do assistente e a API retoma de onde parou
            b.addMessage(msg);
        }
        String stop = msg.stopReason().map(StopReason::asString).orElse(null);
        String category = msg.stopDetails().flatMap(d -> d.category()).map(c -> c.asString()).orElse(null);
        return new Result(text.toString().trim(), dedupe(citations), stop, category, in, out, msg.model().asString());
    }

    /** Saída estruturada validada contra o esquema da classe (sem citações: os dois recursos não se combinam). */
    public <T> Structured<T> extract(String apiKey, String system, List<ContentBlockParam> userContent, Class<T> type) {
        MessageCreateParams.Builder b = base(system, OutputConfig.Effort.MEDIUM);
        b.addUserMessageOfContentBlockParams(userContent);
        StructuredMessageCreateParams<T> params = b.outputConfig(type, JsonSchemaLocalValidation.YES).build();
        StructuredMessage<T> msg = call(() -> client(apiKey).messages().create(params));
        T value = msg.content().stream().flatMap(cb -> cb.text().stream()).map(t -> t.text()).findFirst().orElse(null);
        return new Structured<>(value, msg.stopReason().map(StopReason::asString).orElse(null), msg.usage().inputTokens(),
                msg.usage().outputTokens(), model);
    }

    private static void collect(Message msg, StringBuilder text, List<Citation> citations) {
        for (ContentBlock block : msg.content()) {
            if (block.text().isEmpty()) {
                continue;
            }
            TextBlock t = block.text().get();
            text.append(t.text());
            for (TextCitation c : t.citations().orElse(List.of())) {
                c.webSearchResultLocation().ifPresent(w -> citations.add(new Citation(w.url(), w.title().orElse(null), w.citedText())));
            }
        }
    }

    private static List<Citation> dedupe(List<Citation> list) {
        Map<String, Citation> byUrl = new LinkedHashMap<>();
        for (Citation c : list) {
            byUrl.putIfAbsent(c.url(), c);
        }
        return new ArrayList<>(byUrl.values());
    }

    private interface Call<R> {
        R run();
    }

    /** Converte os erros tipados do SDK em mensagens claras, sem expor detalhes da chave. */
    private static <R> R call(Call<R> c) {
        try {
            return c.run();
        } catch (UnauthorizedException | PermissionDeniedException e) {
            throw new BusinessException("AI_CREDENTIALS", "A chave da IA foi recusada pela Anthropic. Revise a configuração em Integrações.");
        } catch (RateLimitException e) {
            throw new BusinessException("AI_RATE_LIMITED", "Limite de uso da IA atingido no momento. Tente novamente em alguns instantes.");
        } catch (BadRequestException e) {
            log.warn("Pedido recusado pela API de IA: {}", e.getMessage());
            throw new BusinessException("AI_BAD_REQUEST", "A IA não aceitou o pedido (conteúdo grande demais ou formato inválido).");
        } catch (AnthropicServiceException e) {
            log.warn("Falha na API de IA: status {}", e.statusCode(), e);
            throw new BusinessException("AI_UNAVAILABLE", "O serviço de IA está indisponível agora. Tente novamente em alguns minutos.");
        } catch (AnthropicIoException e) {
            log.warn("Sem conexão com a API de IA", e);
            throw new BusinessException("AI_UNAVAILABLE", "Não foi possível conectar ao serviço de IA.");
        }
    }

    private static String sha(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
