package com.navneet.executespec.security.dependency;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * Default scanner — does nothing, reports nothing. Drops out the moment any other
 * {@link SupplyChainScanner} bean is present (org wired in Socket.dev / Snyk / etc.).
 *
 * <p>Lets the rest of the dep-security stack ship today without forcing every operator into a
 * vendor decision. Once an org's procurement is sorted, swap in a real implementation and
 * restart — no other code path changes.
 */
@Component
@ConditionalOnMissingBean(value = SupplyChainScanner.class, ignored = NoopSupplyChainScanner.class)
public class NoopSupplyChainScanner implements SupplyChainScanner {

  @Override
  public boolean isEnabled() {
    return false;
  }

  @Override
  public List<Advisory> scan(List<PackageCoordinate> packages) {
    return List.of();
  }
}
