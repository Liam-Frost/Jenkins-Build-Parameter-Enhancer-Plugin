package io.jenkins.plugins.bpe.model;

import java.util.List;

/** Thrown when a layout received from a client is malformed. */
public class LayoutValidationException extends Exception {
    private static final long serialVersionUID = 1L;

    private final List<String> errors;

    public LayoutValidationException(List<String> errors) {
        super(String.join("; ", errors));
        this.errors = List.copyOf(errors);
    }

    public List<String> getErrors() {
        return errors;
    }
}
