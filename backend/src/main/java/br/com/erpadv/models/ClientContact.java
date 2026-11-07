package br.com.erpadv.models;

import lombok.Data;

/** Pessoa de contato de um cliente (ex.: responsável jurídico ou financeiro de uma empresa). */
@Data
public class ClientContact {
    private String name;
    private String role;
    private String phone;
    private String email;
}
