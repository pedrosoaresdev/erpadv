package br.com.erpadv.models;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** {@code offices/{officeId}/clients/{id}}. CPF/CNPJ e RG ficam apenas criptografados. */
@Data
@EqualsAndHashCode(callSuper = true)
public class Client extends BaseEntity {
    private ClientType type;
    private String name;
    private String nameLower;
    /** Nome fantasia (PJ). */
    private String tradeName;
    private String documentEnc;
    private String documentHash;
    private String documentMasked;
    private String rgEnc;
    private LocalDate birthDate;
    private String phone;
    private String whatsapp;
    private String email;
    private Address address;
    private String profession;
    private String maritalStatus;
    private String notes;
    private ClientStatus status;
    private boolean defaulting;
    /** Inadimplência marcada automaticamente pelo financeiro (contas a receber vencidas). */
    private boolean defaultingAuto;
    private String responsibleUid;
    private String nationality;
    /** PJ: inscrições estadual e municipal, representante legal e contatos. */
    private String stateRegistration;
    private String municipalRegistration;
    private String legalRepresentative;
    private String legalRepresentativeRole;
    private List<ClientContact> contacts = new ArrayList<>();
    /** Origem do cliente (mesmos códigos do CRM: INDICACAO, SITE, GOOGLE…). */
    private String source;
    private String sourceDetail;
    /** Último atendimento/movimentação relevante (para "sem movimentação recente"). */
    private Instant lastActivityAt;
    private Instant inactivatedAt;
    private String inactivationReason;
    private List<String> searchTokens = new ArrayList<>();
    /** Preenchido quando o cliente foi anonimizado (LGPD): dados pessoais removidos, histórico financeiro mantido. */
    private Instant anonymizedAt;
}
