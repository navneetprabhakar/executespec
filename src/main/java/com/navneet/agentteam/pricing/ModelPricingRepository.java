package com.navneet.agentteam.pricing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ModelPricingRepository extends JpaRepository<ModelPricing, ModelPricingId> {

    List<ModelPricing> findByIdProvider(String provider);

    Optional<ModelPricing> findByIdProviderAndIdModel(String provider, String model);
}
