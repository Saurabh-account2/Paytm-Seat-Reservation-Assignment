package com.paytm.seatreservation.repository;

import com.paytm.seatreservation.entity.Seat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface SeatRepository extends JpaRepository<Seat, UUID> {

    List<Seat> findByShowIdOrderBySeatNumber(UUID showId);

    /**
     * SELECT FOR UPDATE in deterministic seat_number order — this is the atomic lock.
     */
    @Query(value = """
        SELECT * FROM seats
        WHERE show_id = :showId AND seat_number IN :numbers
        ORDER BY seat_number
        FOR UPDATE
        """, nativeQuery = true)
    List<Seat> lockSeatsForUpdate(@Param("showId") UUID showId,
                                  @Param("numbers") List<String> numbers);

    @Query("SELECT COUNT(s) FROM Seat s WHERE s.showId = :showId AND s.userId = :userId AND s.status IN ('confirmed','held')")
    long countActiveByShowAndUser(@Param("showId") UUID showId,
                                  @Param("userId") String userId);

    @Query("SELECT COUNT(s) FROM Seat s WHERE s.showId = :showId AND s.status = :status")
    long countByShowIdAndStatus(@Param("showId") UUID showId,
                                @Param("status") String status);

    /**
     * Expire held seats whose hold window has passed.
     */
    @Modifying
    @Query("""
        UPDATE Seat s SET s.status = 'available', s.userId = NULL,
               s.reservationId = NULL, s.heldUntil = NULL
        WHERE s.status = 'held' AND s.heldUntil < :now
        """)
    int expireHeldSeats(@Param("now") Instant now);

    /**
     * Release seats belonging to a specific reservation.
     */
    @Modifying
    @Query("""
        UPDATE Seat s SET s.status = 'available', s.userId = NULL,
               s.reservationId = NULL, s.heldUntil = NULL
        WHERE s.reservationId = :reservationId
        """)
    int releaseByReservationId(@Param("reservationId") UUID reservationId);
}
