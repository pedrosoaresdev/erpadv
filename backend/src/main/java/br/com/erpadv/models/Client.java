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
    private Instant inactivatedAt;
    private String inactivationReason;
    private List<String> searchTokens = new ArrayList<>();
}
