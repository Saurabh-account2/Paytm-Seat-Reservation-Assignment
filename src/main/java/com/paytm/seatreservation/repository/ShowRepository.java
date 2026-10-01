package com.paytm.seatreservation.repository;

import com.paytm.seatreservation.entity.Show;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface ShowRepository extends JpaRepository<Show, UUID> {
}
