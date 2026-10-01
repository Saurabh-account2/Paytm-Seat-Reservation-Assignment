package com.paytm.seatreservation.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "reservations")
public class Reservation {
    @Id
    private UUID id;

    @Column(name = "show_id", nullable = false)
    private UUID showId;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "seats_csv", nullable = false)
    private String seatsCsv;

    @Column(name = "seat_count", nullable = false)
    private int seatCount;

    @Column(name = "amount_paise", nullable = false)
    private long amountPaise;

    @Column(nullable = false)
    private String status = "confirmed";

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public Reservation() {}

    @Transient
    public List<String> getSeats() {
        return Arrays.asList(seatsCsv.split(","));
    }

    public void setSeats(List<String> seats) {
        this.seatsCsv = String.join(",", seats);
        this.seatCount = seats.size();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getShowId() { return showId; }
    public void setShowId(UUID showId) { this.showId = showId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getSeatsCsv() { return seatsCsv; }
    public void setSeatsCsv(String csv) { this.seatsCsv = csv; }
    public int getSeatCount() { return seatCount; }
    public void setSeatCount(int c) { this.seatCount = c; }
    public long getAmountPaise() { return amountPaise; }
    public void setAmountPaise(long a) { this.amountPaise = a; }
    public String getStatus() { return status; }
    public void setStatus(String s) { this.status = s; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String k) { this.idempotencyKey = k; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant t) { this.createdAt = t; }
}
