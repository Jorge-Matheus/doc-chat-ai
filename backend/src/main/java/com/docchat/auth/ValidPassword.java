package com.docchat.auth;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.charset.StandardCharsets;

/**
 * At least 12 characters and at most 72 bytes. The upper limit is in bytes because BCrypt only
 * accepts 72, and a shorter password can exceed it when it has accented letters or symbols.
 */
@Constraint(validatedBy = ValidPassword.Validator.class)
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@interface ValidPassword {

    String message() default "Use at least 12 characters and no more than 72 bytes.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<ValidPassword, String> {

        private static final int MIN_CHARACTERS = 12;
        private static final int MAX_BYTES = 72;

        @Override
        public boolean isValid(String password, ConstraintValidatorContext context) {
            return password != null
                    && password.codePointCount(0, password.length()) >= MIN_CHARACTERS
                    && password.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES;
        }
    }
}
