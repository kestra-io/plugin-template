package io.kestra.plugin.langchain.exceptions;

import java.io.Serial;

public class ResourceNotFound extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    public ResourceNotFound(Throwable e) {
        super(e);
    }

    public ResourceNotFound(String message) {
        super(message);
    }

    public ResourceNotFound(String message, Throwable e) {
        super(message, e);
    }
}

