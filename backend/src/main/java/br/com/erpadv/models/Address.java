package br.com.erpadv.models;

import lombok.Data;

@Data
public class Address {
    private String zipCode;
    private String street;
    private String number;
    private String complement;
    private String neighborhood;
    private String city;
    private String state;
}
