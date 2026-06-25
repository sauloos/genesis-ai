package com.genesisai.repository;

import com.genesisai.model.TrainingSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TrainingSessionRepository extends JpaRepository<TrainingSession, String> {
    List<TrainingSession> findAllByOrderByUpdatedAtDesc();
}
