package br.com.erpadv.models;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.ArrayList;
import java.util.List;

/** Plano de assinatura da plataforma ({@code plans/{code}}), mantido pelo painel master. */
@Data
@EqualsAndHashCode(callSuper = true)
public class Plan extends BaseEntity {
    private String code;
    private String name;
    private String description;
    /** Preços em centavos; vazios até a plataforma definir (sem preço não há cobrança). */
    private Long monthlyPriceCents;
    private Long yearlyPriceCents;
    /** Vazio = ilimitado. */
    private Integer maxUsers;
    private Integer maxStorageMb;
    /** Módulos opcionais incluídos (o núcleo — clientes, processos, prazos, agenda, documentos — está em todos). */
    private List<String> modules = new ArrayList<>();
    private int trialDays;
    private boolean active = true;
    private boolean highlight;
    private int sortOrder;
}
