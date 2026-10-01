package com.paytm.seatreservation.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.UUID;

public record ReservationResponse(
    @JsonProperty("reservation_id") UUID reservationId,
    @JsonProperty("show_id") UUID showId,
    @JsonProperty("user_id") String userId,
    List<String> seats,
    @JsonProperty("amount_paise") long amountPaise,
    String status,
    @JsonIgnore boolean replay    // not serialized — used by controller for status code
) {
    /** Convenience constructor for non-replay responses */
    public ReservationResponse(UUID reservationId, UUID showId, String userId,
                                List<String> seats, long amountPaise, String status) {
        this(reservationId, showId, userId, seats, amountPaise, status, false);
    }
}
