package com.navneet.executespec.pricing;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ModelPricingRepository extends JpaRepository<ModelPricing, ModelPricingId> {

  List<ModelPricing> findByIdProvider(String provider);

  Optional<ModelPricing> findByIdProviderAndIdModel(String provider, String model);
}
