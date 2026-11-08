package br.com.erpadv.models;

import lombok.Data;

/** Convidado externo (cliente, perito, testemunha) — o envio de convites por e-mail entra com a integração de e-mail. */
@Data
public class AppointmentGuest {
    private String name;
    private String email;
}
