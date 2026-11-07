package br.com.erpadv.services;

import br.com.erpadv.dto.BoardDtos.BoardInsights;
import br.com.erpadv.dto.CrmDtos.CrmMetrics;
import br.com.erpadv.exceptions.BusinessException;
import br.com.erpadv.exceptions.ConflictException;
import br.com.erpadv.models.Board;
import br.com.erpadv.models.Board.BoardColumn;
import br.com.erpadv.models.Board.BoardLabel;
import br.com.erpadv.models.BoardCard;
import br.com.erpadv.models.CrmEnums.LeadSource;
import br.com.erpadv.models.CrmEnums.LeadStage;
import br.com.erpadv.models.Lead;
import br.com.erpadv.security.AuthenticatedUser;
import br.com.erpadv.security.Permission;
import br.com.erpadv.security.Role;
import br.com.erpadv.security.RolePermissions;
import br.com.erpadv.validation.PasswordPolicy;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BoardAndCrmUnitTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 9);
    private static final Instant NOW = Instant.parse("2026-11-09T15:00:00Z");
    private static final AuthenticatedUser USER = new AuthenticatedUser("u1", "u1@x.test", "Ana", true, "o1",
            Role.ADVOGADO, null, Set.of(Permission.BOARDS_USE));

    // ------------------------------------------------------------------ quadros

    private static BoardColumn column(String id, String name, int wip, boolean done) {
        BoardColumn c = new BoardColumn();
        c.setId(id);
        c.setName(name);
        c.setWipLimit(wip);
        c.setDone(done);
        return c;
    }

    private static BoardCard card(String id, String column, LocalDate due, List<String> assignees) {
        BoardCard c = new BoardCard();
        c.setId(id);
        c.setColumnId(column);
        c.setDueDate(due);
        c.setAssigneeUids(new ArrayList<>(assignees));
        c.setCreatedAt(NOW.minus(10, ChronoUnit.DAYS));
        c.setColumnEnteredAt(NOW.minus(1, ChronoUnit.DAYS));
        return c;
    }

    private static Board board() {
        Board b = new Board();
        b.setColumns(List.of(column("a", "Triagem", 0, false), column("b", "Revisão", 2, false),
                column("z", "Concluído", 0, true)));
        BoardLabel urgent = new BoardLabel();
        urgent.setId("l1");
        urgent.setName("Urgente");
        urgent.setColor("#c62828");
        b.setLabels(List.of(urgent));
        return b;
    }

    @Test
    void limiteWipBloqueiaEntradaMasIgnoraOProprioCartao() {
        BoardColumn review = column("b", "Revisão", 2, false);
        List<BoardCard> cards = List.of(card("1", "b", null, List.of()), card("2", "b", null, List.of()));
        assertThatThrownBy(() -> BoardService.assertWip(review, cards, "3"))
                .isInstanceOf(ConflictException.class).hasMessageContaining("limite de 2");
        BoardService.assertWip(review, cards, "2");
        BoardService.assertWip(column("a", "Triagem", 0, false), cards, "3");
    }

    @Test
    void automacoesDaColunaAplicamEtiquetaResponsavelVencimentoEConclusao() {
        Board b = board();
        BoardColumn review = b.getColumns().get(1);
        review.getAutomation().setAddLabelId("l1");
        review.getAutomation().setAssignUid("u9");
        review.getAutomation().setSetDueInDays(3);
        BoardCard c = card("1", "a", null, List.of("u1"));
        List<String> notes = BoardService.runAutomations(USER, b, review, c);
        assertThat(c.getLabelIds()).containsExactly("l1");
        assertThat(c.getAssigneeUids()).containsExactly("u1", "u9");
        assertThat(c.getDueDate()).isNotNull();
        assertThat(notes).hasSize(3);

        BoardService.runAutomations(USER, b, b.getColumns().get(2), c);
        assertThat(c.isCompleted()).isTrue();
        assertThat(c.getCompletedAt()).isNotNull();

        List<String> reopened = BoardService.runAutomations(USER, b, b.getColumns().get(0), c);
        assertThat(c.isCompleted()).isFalse();
        assertThat(reopened).contains("cartão reaberto");
    }

    @Test
    void indicadoresApontamAtrasosGargalosECarga() {
        Board b = board();
        List<BoardCard> cards = new ArrayList<>();
        BoardCard late = card("1", "a", TODAY.minusDays(2), List.of("u1"));
        BoardCard stale1 = card("2", "b", TODAY.plusDays(3), List.of("u1"));
        stale1.setColumnEnteredAt(NOW.minus(9, ChronoUnit.DAYS));
        BoardCard stale2 = card("3", "b", null, List.of());
        stale2.setColumnEnteredAt(NOW.minus(12, ChronoUnit.DAYS));
        BoardCard done = card("4", "z", null, List.of("u2"));
        done.setCompleted(true);
        done.setCompletedAt(NOW.minus(2, ChronoUnit.DAYS));
        cards.addAll(List.of(late, stale1, stale2, done));

        BoardInsights i = BoardService.computeInsights(b, cards, TODAY, NOW, Map.of("u1", "Ana", "u2", "Bruno"));
        assertThat(i.open()).isEqualTo(3);
        assertThat(i.completed()).isEqualTo(1);
        assertThat(i.overdue()).isEqualTo(1);
        assertThat(i.dueThisWeek()).isEqualTo(1);
        assertThat(i.unassigned()).isEqualTo(1);
        assertThat(i.stale()).isEqualTo(2);
        assertThat(i.completed7d()).isEqualTo(1);
        assertThat(i.avgCycleDays()).isEqualTo(8.0);
        assertThat(i.throughputWeeks()).hasSize(6).last().isEqualTo(1L);
        assertThat(i.columns().get(1).cards()).isEqualTo(2);
        assertThat(i.alerts()).anyMatch(a -> a.contains("limite WIP"))
                .anyMatch(a -> a.contains("Gargalo em \"Revisão\""))
                .anyMatch(a -> a.contains("vencimento ultrapassado"));
        assertThat(i.members()).first().satisfies(m -> {
            assertThat(m.name()).isEqualTo("Ana");
            assertThat(m.open()).isEqualTo(2);
            assertThat(m.overdue()).isEqualTo(1);
        });
    }

    @Test
    void ordenaPrioridades() {
        assertThat(BoardService.priorityRank("URGENTE")).isLessThan(BoardService.priorityRank("ALTA"));
        assertThat(BoardService.priorityRank("MEDIA")).isLessThan(BoardService.priorityRank("BAIXA"));
    }

    // ------------------------------------------------------------------ CRM

    private static Lead lead(LeadStage stage, LeadSource source, long value) {
        Lead l = new Lead();
        l.setId(stage + "-" + value);
        l.setName("Lead");
        l.setStage(stage);
        l.setSource(source);
        l.setEstimatedValueCents(value);
        l.setCreatedAt(NOW.minus(20, ChronoUnit.DAYS));
        l.setLastActivityAt(NOW.minus(1, ChronoUnit.DAYS));
        l.setNextActionDate(TODAY.plusDays(1));
        return l;
    }

    @Test
    void pontuacaoConsideraEtapaValorOrigemERecencia() {
        Lead hot = lead(LeadStage.NEGOCIACAO, LeadSource.INDICACAO, 2_000_000);
        assertThat(CrmService.score(hot, TODAY, NOW)).isEqualTo(100);
        assertThat(CrmService.temperature(CrmService.score(hot, TODAY, NOW))).isEqualTo("QUENTE");

        Lead cold = lead(LeadStage.NOVO, LeadSource.SITE, 0);
        cold.setLastActivityAt(NOW.minus(30, ChronoUnit.DAYS));
        cold.setNextActionDate(TODAY.minusDays(3));
        assertThat(CrmService.score(cold, TODAY, NOW)).isZero();
        assertThat(CrmService.temperature(0)).isEqualTo("FRIO");

        assertThat(CrmService.score(lead(LeadStage.GANHO, LeadSource.SITE, 0), TODAY, NOW)).isEqualTo(100);
        assertThat(CrmService.score(lead(LeadStage.PERDIDO, LeadSource.SITE, 0), TODAY, NOW)).isZero();
    }

    @Test
    void metricasDoFunilCalculamConversaoEPipelinePonderado() {
        Lead won = lead(LeadStage.GANHO, LeadSource.INDICACAO, 500_000);
        won.setWonAt(NOW.minus(10, ChronoUnit.DAYS));
        Lead lost = lead(LeadStage.PERDIDO, LeadSource.GOOGLE, 100_000);
        lost.setLostReason("Preço");
        Lead proposal = lead(LeadStage.PROPOSTA, LeadSource.INDICACAO, 1_000_000);
        Lead fresh = lead(LeadStage.NOVO, LeadSource.GOOGLE, 200_000);
        fresh.setNextActionDate(TODAY.minusDays(1));

        CrmMetrics m = CrmService.computeMetrics(List.of(won, lost, proposal, fresh), 6, TODAY, NOW);
        assertThat(m.openLeads()).isEqualTo(2);
        assertThat(m.pipelineValueCents()).isEqualTo(1_200_000);
        assertThat(m.weightedPipelineCents()).isEqualTo(600_000 + 20_000);
        assertThat(m.wonCount()).isEqualTo(1);
        assertThat(m.lostCount()).isEqualTo(1);
        assertThat(m.conversionRate()).isEqualTo(50.0);
        assertThat(m.avgDaysToWin()).isEqualTo(10.0);
        assertThat(m.overdueActions()).isEqualTo(1);
        assertThat(m.funnel()).hasSize(LeadStage.values().length);
        assertThat(m.sources()).extracting(s -> s.source()).contains(LeadSource.INDICACAO, LeadSource.GOOGLE);
        assertThat(m.sources().stream().filter(s -> s.source() == LeadSource.INDICACAO).findFirst().orElseThrow()
                .conversionRate()).isEqualTo(100.0);
        assertThat(m.months()).hasSize(6);
        assertThat(m.lostReasons()).containsExactly("preço (1)");
    }

    // ------------------------------------------------------------------ cargos e senhas

    @Test
    void cargoPersonalizadoNuncaRecebePlataformaNemPortal() {
        Set<Permission> effective = RolePermissions.effectiveCustom(
                List.of(Permission.CLIENTS_READ, Permission.PLATFORM_ADMIN, Permission.PORTAL_ACCESS),
                List.of(Permission.CRM_USE), List.of(Permission.CLIENTS_READ));
        assertThat(effective).containsExactly(Permission.CRM_USE);
    }

    @Test
    void novasPermissoesEstaoNosPapeisPadrao() {
        assertThat(RolePermissions.defaultsFor(Role.ADVOGADO)).contains(Permission.BOARDS_USE, Permission.CRM_USE);
        assertThat(RolePermissions.defaultsFor(Role.ESTAGIARIO)).contains(Permission.BOARDS_USE)
                .doesNotContain(Permission.CRM_USE);
        assertThat(RolePermissions.defaultsFor(Role.SOCIO_ADMIN)).contains(Permission.BOARDS_USE, Permission.CRM_USE)
                .doesNotContain(Permission.PLATFORM_ADMIN);
    }

    @Test
    void politicaDeSenhaExigeTamanhoLetrasENumeros() {
        assertThat(PasswordPolicy.isStrong("Senha-Forte-123")).isTrue();
        assertThat(PasswordPolicy.isStrong("curta1")).isFalse();
        assertThat(PasswordPolicy.isStrong("somenteletras")).isFalse();
        assertThat(PasswordPolicy.isStrong("12345678901")).isFalse();
        assertThatThrownBy(() -> PasswordPolicy.check("abc")).isInstanceOf(BusinessException.class);
    }

    @Test
    void validaCpfDoAdministrador() {
        assertThat(PlatformService.validCpf("529.982.247-25")).isEqualTo("52998224725");
        assertThatThrownBy(() -> PlatformService.validCpf("111.111.111-11")).isInstanceOf(BusinessException.class);
    }
}
