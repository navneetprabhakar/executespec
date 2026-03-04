package com.navneet.agentteam.auth.repository;

import com.navneet.agentteam.auth.entity.Org;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface OrgRepository extends JpaRepository<Org, String> {

    Optional<Org> findByName(String name);

    @Query("SELECT o FROM Org o WHERE o.status != 'DELETED' ORDER BY o.createdAt DESC")
    List<Org> findAllActive();
}
