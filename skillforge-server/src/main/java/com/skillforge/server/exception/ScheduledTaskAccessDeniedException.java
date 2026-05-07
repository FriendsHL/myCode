package com.skillforge.server.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * P12: a user attempted to read or modify a scheduled task they do not own.
 * Spring maps this to HTTP 403 via {@link ResponseStatus}.
 */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class ScheduledTaskAccessDeniedException extends RuntimeException {
    public ScheduledTaskAccessDeniedException(String message) {
        super(message);
    }
}
