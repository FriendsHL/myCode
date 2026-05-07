package com.skillforge.server.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class ScheduledTaskNotFoundException extends RuntimeException {
    public ScheduledTaskNotFoundException(Long id) {
        super("Scheduled task not found: id=" + id);
    }
}
