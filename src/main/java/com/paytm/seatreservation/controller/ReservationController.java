package com.paytm.seatreservation.controller;

import com.paytm.seatreservation.dto.ReservationResponse;
import com.paytm.seatreservation.dto.ReserveRequest;
import com.paytm.seatreservation.service.ReservationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
public class ReservationController {

    private final ReservationService reservationService;

    public ReservationController(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    @PostMapping("/shows/{showId}/reserve")
    public ResponseEntity<ReservationResponse> reserve(
            @PathVariable UUID showId,
            @RequestBody ReserveRequest request,
            HttpServletRequest httpRequest) {

        String userId = (String) httpRequest.getAttribute("userId");
        ReservationResponse response = reservationService.reserve(showId, userId, request);

        // 200 for idempotent replay, 201 for new reservation
        HttpStatus status = response.replay() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(response);
    }

    @PostMapping("/reservations/{id}/cancel")
    public ResponseEntity<ReservationResponse> cancel(
            @PathVariable UUID id,
            HttpServletRequest httpRequest) {

        String userId = (String) httpRequest.getAttribute("userId");
        ReservationResponse response = reservationService.cancel(id, userId);
        return ResponseEntity.ok(response);
    }
}
