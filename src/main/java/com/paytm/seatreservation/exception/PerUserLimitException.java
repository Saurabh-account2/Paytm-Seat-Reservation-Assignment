package com.paytm.seatreservation.exception;

public class PerUserLimitException extends RuntimeException {
    public PerUserLimitException(int limit) {
        super("Per-user limit of " + limit + " seats exceeded");
    }
}
