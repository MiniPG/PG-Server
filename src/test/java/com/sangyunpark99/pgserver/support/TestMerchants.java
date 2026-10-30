package com.sangyunpark99.pgserver.support;

import com.sangyunpark99.pgserver.merchant.application.SecretKeyHasher;
import com.sangyunpark99.pgserver.merchant.domain.Merchant;
import com.sangyunpark99.pgserver.merchant.infrastructure.MerchantRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class TestMerchants {
    private final MerchantRepository merchantRepository;

    public TestMerchants(MerchantRepository merchantRepository) {
        this.merchantRepository = merchantRepository;
    }

    public String authorization(String mId) {
        String secretKey = secretKeyOf(mId);
        String hash = SecretKeyHasher.hash(secretKey);
        if (merchantRepository.findBySecretKeyHash(hash).isEmpty()) {
            merchantRepository.save(Merchant.create(mId, mId, hash, LocalDateTime.now()));
        }
        return bearer(secretKey);
    }

    public static String secretKeyOf(String mId) {
        return "test_sk_" + mId;
    }

    public static String bearer(String secretKey) {
        return "Bearer " + secretKey;
    }
}
