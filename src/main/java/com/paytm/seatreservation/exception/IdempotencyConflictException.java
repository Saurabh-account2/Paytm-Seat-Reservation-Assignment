package com.paytm.seatreservation.exception;

public class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException() {
        super("Idempotency key already used with different request body");
    }
}
