package com.paytm.seatreservation.exception;

import java.util.List;

public class SeatTakenException extends RuntimeException {
    private final List<String> takenSeats;
    public SeatTakenException(List<String> takenSeats) {
        super("Seats already taken: " + takenSeats);
        this.takenSeats = takenSeats;
    }
    public List<String> getTakenSeats() { return takenSeats; }
}
