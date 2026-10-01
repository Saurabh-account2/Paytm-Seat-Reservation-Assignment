package com.paytm.seatreservation.service;

import com.paytm.seatreservation.dto.*;
import com.paytm.seatreservation.entity.Seat;
import com.paytm.seatreservation.entity.Show;
import com.paytm.seatreservation.exception.NotFoundException;
import com.paytm.seatreservation.repository.SeatRepository;
import com.paytm.seatreservation.repository.ShowRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ShowService {

    private static final Logger log = LoggerFactory.getLogger(ShowService.class);

    private final ShowRepository showRepository;
    private final SeatRepository seatRepository;
    private final MeterRegistry meterRegistry;

    // Track show IDs for gauge registration
    private final ConcurrentHashMap<UUID, Boolean> registeredGauges = new ConcurrentHashMap<>();

    public ShowService(ShowRepository showRepository,
                       SeatRepository seatRepository,
                       MeterRegistry meterRegistry) {
        this.showRepository = showRepository;
        this.seatRepository = seatRepository;
        this.meterRegistry = meterRegistry;
    }

    @Transactional
    public ShowResponse createShow(CreateShowRequest request) {
        if (request.name() == null || request.name().isBlank()) {
            throw new IllegalArgumentException("Show name is required");
        }
        if (request.seats() == null || request.seats().isEmpty()) {
            throw new IllegalArgumentException("At least one seat is required");
        }
        if (request.pricePaise() < 0) {
            throw new IllegalArgumentException("Price must be non-negative");
        }

        UUID showId = UUID.randomUUID();

        Show show = new Show();
        show.setId(showId);
        show.setName(request.name());
        show.setPricePaise(request.pricePaise());
        show.setPerUserLimit(request.resolvedLimit());
        show.setTotalSeats(request.seats().size());
        show.setCreatedAt(Instant.now());
        showRepository.save(show);

        // Create seat rows
        List<Seat> seats = request.seats().stream().map(number -> {
            Seat seat = new Seat();
            seat.setId(UUID.randomUUID());
            seat.setShowId(showId);
            seat.setSeatNumber(number);
            seat.setStatus("available");
            return seat;
        }).toList();
        seatRepository.saveAll(seats);

        // Register Prometheus gauge for this show
        registerGauge(showId, show.getName());

        log.info("Show created: id={} name={} seats={}", showId, show.getName(), seats.size());

        return buildResponse(show, seats);
    }

    @Transactional(readOnly = true)
    public ShowResponse getShow(UUID showId) {
        Show show = showRepository.findById(showId)
                .orElseThrow(() -> new NotFoundException("Show not found: " + showId));

        List<Seat> seats = seatRepository.findByShowIdOrderBySeatNumber(showId);
        return buildResponse(show, seats);
    }

    private ShowResponse buildResponse(Show show, List<Seat> seats) {
        int available = 0, held = 0, confirmed = 0;
        for (Seat s : seats) {
            switch (s.getStatus()) {
                case "available" -> available++;
                case "held" -> held++;
                case "confirmed" -> confirmed++;
            }
        }

        List<SeatResponse> seatDtos = seats.stream()
                .map(s -> new SeatResponse(s.getSeatNumber(), s.getStatus()))
                .toList();

        return new ShowResponse(
                show.getId(), show.getName(), show.getPricePaise(),
                show.getPerUserLimit(), show.getTotalSeats(),
                available, held, confirmed,
                seatDtos, show.getCreatedAt());
    }

    private void registerGauge(UUID showId, String showName) {
        registeredGauges.computeIfAbsent(showId, id -> {
            Gauge.builder("seats_available", () ->
                            seatRepository.countByShowIdAndStatus(showId, "available"))
                    .tag("show_id", showId.toString())
                    .tag("show_name", showName)
                    .register(meterRegistry);
            return true;
        });
    }
}
