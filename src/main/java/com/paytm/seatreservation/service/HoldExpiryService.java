package com.paytm.seatreservation.service;

import com.paytm.seatreservation.repository.SeatRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class HoldExpiryService {

    private static final Logger log = LoggerFactory.getLogger(HoldExpiryService.class);

    private final SeatRepository seatRepository;

    public HoldExpiryService(SeatRepository seatRepository) {
        this.seatRepository = seatRepository;
    }

    /**
     * Runs every 30 seconds to expire held seats whose TTL has passed.
     */
    @Scheduled(fixedRate = 30_000)
    @Transactional
    public void expireHolds() {
        int expired = seatRepository.expireHeldSeats(Instant.now());
        if (expired > 0) {
            log.info("Expired {} held seats", expired);
        }
    }
}
