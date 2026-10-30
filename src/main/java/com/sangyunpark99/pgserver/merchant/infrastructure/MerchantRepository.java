package com.sangyunpark99.pgserver.merchant.infrastructure;

import com.sangyunpark99.pgserver.merchant.domain.Merchant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MerchantRepository extends JpaRepository<Merchant, Long> {

    Optional<Merchant> findBySecretKeyHash(String secretKeyHash);
}
