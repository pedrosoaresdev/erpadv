package br.com.erpadv.validation;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Número único de processo no padrão CNJ. Nulo/vazio é aceito (processo ainda não distribuído). */
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = CnjNumber.Validator.class)
public @interface CnjNumber {
    String message() default "Número CNJ inválido (formato NNNNNNN-DD.AAAA.J.TR.OOOO)";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<CnjNumber, String> {
        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return value == null || value.isBlank() || BrazilianDocuments.isValidCnj(value);
        }
    }
}
