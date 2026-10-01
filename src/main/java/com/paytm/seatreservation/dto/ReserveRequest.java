package com.paytm.seatreservation.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record ReserveRequest(
    List<String> seats,
    @JsonProperty("idempotency_key") String idempotencyKey
) {}
