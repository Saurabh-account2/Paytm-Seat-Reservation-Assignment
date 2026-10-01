package com.paytm.seatreservation.service;

import com.paytm.seatreservation.dto.ReservationResponse;
import com.paytm.seatreservation.dto.ReserveRequest;
import com.paytm.seatreservation.entity.Reservation;
import com.paytm.seatreservation.entity.Seat;
import com.paytm.seatreservation.entity.Show;
import com.paytm.seatreservation.exception.*;
import com.paytm.seatreservation.repository.ReservationRepository;
import com.paytm.seatreservation.repository.SeatRepository;
import com.paytm.seatreservation.repository.ShowRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ReservationService {

    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);

    private final ShowRepository showRepository;
    private final SeatRepository seatRepository;
    private final ReservationRepository reservationRepository;
    private final EntityManager entityManager;

    private final Counter confirmedCounter;
    private final Counter seatTakenCounter;
    private final Counter perUserLimitCounter;
    private final Counter idempotentReplayCounter;
    private final Counter idempotentConflictCounter;

    public ReservationService(ShowRepository showRepository,
                              SeatRepository seatRepository,
                              ReservationRepository reservationRepository,
                              EntityManager entityManager,
                              MeterRegistry meterRegistry) {
        this.showRepository = showRepository;
        this.seatRepository = seatRepository;
        this.reservationRepository = reservationRepository;
        this.entityManager = entityManager;

        this.confirmedCounter = Counter.builder("reservations_total")
                .tag("reason", "confirmed").register(meterRegistry);
        this.seatTakenCounter = Counter.builder("reservations_total")
                .tag("reason", "seat_taken").register(meterRegistry);
        this.perUserLimitCounter = Counter.builder("reservations_total")
                .tag("reason", "per_user_limit").register(meterRegistry);
        this.idempotentReplayCounter = Counter.builder("reservations_total")
                .tag("reason", "idempotent_replay").register(meterRegistry);
        this.idempotentConflictCounter = Counter.builder("reservations_total")
                .tag("reason", "idempotent_conflict").register(meterRegistry);
    }

    /**
     * Reserve seats for a user. This is the hot path — every correctness
     * guarantee lives here.
     *
     * Atomic decision mechanism:
     * 1. pg_advisory_xact_lock on hash(showId, userId) serialises all
     *    requests from the same user for the same show → per-user limit
     *    cannot be violated by concurrent requests for different seats.
     * 2. SELECT … FOR UPDATE ORDER BY seat_number acquires row-level
     *    locks in deterministic order → no deadlocks for multi-seat.
     * 3. The UPDATE only proceeds if every requested seat is still
     *    'available' → all-or-nothing semantics.
     * 4. UNIQUE(idempotency_key) on reservations table is the final
     *    fence against double-creation under race.
     */
    @Transactional
    public ReservationResponse reserve(UUID showId, String userId, ReserveRequest request) {
        validateRequest(request);

        // ── 1. Advisory lock: serialise per (show, user) ──
        long lockKey = computeLockKey(showId, userId);
        entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(:lockKey)")
                .setParameter("lockKey", lockKey)
                .getSingleResult();

        // ── 2. Idempotency check ──
        Optional<Reservation> existing = reservationRepository
                .findByIdempotencyKey(request.idempotencyKey());
        if (existing.isPresent()) {
            return handleIdempotentRequest(existing.get(), showId, userId, request);
        }

        // ── 3. Load show ──
        Show show = showRepository.findById(showId)
                .orElseThrow(() -> new NotFoundException("Show not found: " + showId));

        // ── 4. Sort requested seats (deterministic lock order) ──
        List<String> sortedSeats = request.seats().stream().sorted().toList();

        // ── 5. Lock seat rows: SELECT … FOR UPDATE ORDER BY seat_number ──
        List<Seat> lockedSeats = seatRepository.lockSeatsForUpdate(showId, sortedSeats);

        // Validate all requested seats actually exist
        if (lockedSeats.size() != sortedSeats.size()) {
            Set<String> found = lockedSeats.stream()
                    .map(Seat::getSeatNumber).collect(Collectors.toSet());
            List<String> missing = sortedSeats.stream()
                    .filter(s -> !found.contains(s)).toList();
            throw new NotFoundException("Seats not found: " + missing);
        }

        // ── 6. All-or-nothing availability check ──
        List<String> taken = lockedSeats.stream()
                .filter(s -> !"available".equals(s.getStatus()))
                .map(Seat::getSeatNumber)
                .toList();
        if (!taken.isEmpty()) {
            seatTakenCounter.increment();
            log.info("Seats already taken: {} by user {}", taken, userId);
            throw new SeatTakenException(taken);
        }

        // ── 7. Per-user limit (safe because advisory lock serialises this user) ──
        long currentCount = seatRepository.countActiveByShowAndUser(showId, userId);
        if (currentCount + sortedSeats.size() > show.getPerUserLimit()) {
            perUserLimitCounter.increment();
            log.info("Per-user limit hit: user={} current={} requested={} limit={}",
                    userId, currentCount, sortedSeats.size(), show.getPerUserLimit());
            throw new PerUserLimitException(show.getPerUserLimit());
        }

        // ── 8. Create reservation ──
        Reservation reservation = new Reservation();
        reservation.setId(UUID.randomUUID());
        reservation.setShowId(showId);
        reservation.setUserId(userId);
        reservation.setSeats(sortedSeats);
        reservation.setAmountPaise(show.getPricePaise() * sortedSeats.size());
        reservation.setStatus("confirmed");
        reservation.setIdempotencyKey(request.idempotencyKey());
        reservation.setCreatedAt(Instant.now());

        try {
            reservationRepository.saveAndFlush(reservation);
        } catch (DataIntegrityViolationException e) {
            // Race on idempotency key (two requests slipped past the check)
            return handleIdempotencyRace(request, showId, userId);
        }

        // ── 9. Assign seats to this reservation ──
        for (Seat seat : lockedSeats) {
            seat.setStatus("confirmed");
            seat.setUserId(userId);
            seat.setReservationId(reservation.getId());
        }
        seatRepository.saveAll(lockedSeats);

        confirmedCounter.increment();
        log.info("Reservation confirmed: id={} user={} seats={} amount={}",
                reservation.getId(), userId, sortedSeats, reservation.getAmountPaise());

        return toResponse(reservation, false);
    }

    /**
     * Cancel a reservation. Only the owning user may cancel.
     */
    @Transactional
    public ReservationResponse cancel(UUID reservationId, String userId) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new NotFoundException("Reservation not found"));

        if (!reservation.getUserId().equals(userId)) {
            throw new IllegalArgumentException("You can only cancel your own reservations");
        }

        if ("cancelled".equals(reservation.getStatus())) {
            return toResponse(reservation, false);
        }

        // Release the seats back to available
        int released = seatRepository.releaseByReservationId(reservationId);
        reservation.setStatus("cancelled");
        reservationRepository.save(reservation);

        log.info("Reservation cancelled: id={} user={} seats_released={}",
                reservationId, userId, released);
        return toResponse(reservation, false);
    }

    // ─── Private helpers ────────────────────────────────────────

    private void validateRequest(ReserveRequest request) {
        if (request.seats() == null || request.seats().isEmpty()) {
            throw new IllegalArgumentException("At least one seat is required");
        }
        if (request.idempotencyKey() == null || request.idempotencyKey().isBlank()) {
            throw new IllegalArgumentException("Idempotency key is required");
        }
        // Check for duplicate seat numbers in request
        Set<String> unique = new HashSet<>(request.seats());
        if (unique.size() != request.seats().size()) {
            throw new IllegalArgumentException("Duplicate seat numbers in request");
        }
    }

    /**
     * Hash (showId, userId) into a 63-bit positive long for pg_advisory_xact_lock.
     */
    private long computeLockKey(UUID showId, String userId) {
        return Math.abs((long) showId.hashCode() * 31 + userId.hashCode()) & 0x7FFFFFFFFFFFFFFFL;
    }

    private ReservationResponse handleIdempotentRequest(
            Reservation existing, UUID showId, String userId, ReserveRequest request) {

        // Same key, same show, same user, same seats → replay
        if (existing.getShowId().equals(showId)
                && existing.getUserId().equals(userId)
                && sameSeats(existing, request.seats())) {
            idempotentReplayCounter.increment();
            log.info("Idempotent replay: key={}", request.idempotencyKey());
            return toResponse(existing, true);
        }

        // Same key but different request → conflict
        idempotentConflictCounter.increment();
        throw new IdempotencyConflictException();
    }

    private ReservationResponse handleIdempotencyRace(
            ReserveRequest request, UUID showId, String userId) {
        // Another thread won the insert — fetch and compare
        Reservation winner = reservationRepository
                .findByIdempotencyKey(request.idempotencyKey())
                .orElseThrow(() -> new RuntimeException("Idempotency race but no record found"));

        if (winner.getShowId().equals(showId)
                && winner.getUserId().equals(userId)
                && sameSeats(winner, request.seats())) {
            idempotentReplayCounter.increment();
            return toResponse(winner, true);
        }
        idempotentConflictCounter.increment();
        throw new IdempotencyConflictException();
    }

    private boolean sameSeats(Reservation reservation, List<String> requestedSeats) {
        List<String> existing = reservation.getSeats().stream().sorted().toList();
        List<String> requested = requestedSeats.stream().sorted().toList();
        return existing.equals(requested);
    }

    private ReservationResponse toResponse(Reservation r, boolean replay) {
        return new ReservationResponse(
                r.getId(), r.getShowId(), r.getUserId(),
                r.getSeats(), r.getAmountPaise(), r.getStatus(), replay);
    }
}
