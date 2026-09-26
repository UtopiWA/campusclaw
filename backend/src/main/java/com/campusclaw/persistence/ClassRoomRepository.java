package com.campusclaw.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClassRoomRepository extends JpaRepository<ClassRoom, Long> {
    Optional<ClassRoom> findByName(String name);
}

