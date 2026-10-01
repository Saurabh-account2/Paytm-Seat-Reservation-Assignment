package com.paytm.seatreservation.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ShowResponse(
    UUID id,
    String name,
    long pricePaise,
    int perUserLimit,
    int totalSeats,
    int availableCount,
    int heldCount,
    int confirmedCount,
    List<SeatResponse> seats,
    Instant createdAt
) {}
