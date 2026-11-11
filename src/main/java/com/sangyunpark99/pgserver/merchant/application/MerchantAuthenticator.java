package com.sangyunpark99.pgserver.merchant.application;

import com.github.benmanes.caffeine.cache.Cache;
import com.sangyunpark99.pgserver.common.exception.ErrorCode;
import com.sangyunpark99.pgserver.common.exception.PgException;
import com.sangyunpark99.pgserver.merchant.domain.Merchant;
import com.sangyunpark99.pgserver.merchant.infrastructure.MerchantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MerchantAuthenticator {
    private static final String BEARER_PREFIX = "Bearer ";

    private final MerchantRepository merchantRepository;
    private final Cache<String, MerchantAuth> cache;

    public String authenticate(String authorizationHeader) {
        String hash = SecretKeyHasher.hash(extractSecretKey(authorizationHeader));
        return check(load(hash));
    }

    public String authenticatedCached(String authorizationHeader) {
        String hash = SecretKeyHasher.hash(extractSecretKey(authorizationHeader));
        MerchantAuth cached = cache.getIfPresent(hash);
        return check(cached != null ? cached : load(hash));
    }

    private MerchantAuth load(String hash) {
        Merchant merchant = merchantRepository.findBySecretKeyHash(hash)
                .orElseThrow(() -> new PgException(ErrorCode.UNAUTHORIZED_KEY));
        MerchantAuth auth = new MerchantAuth(merchant.getMId(), merchant.isActive());
        cache.put(hash, auth);

        return auth;
    }

    private String check(MerchantAuth auth) {
        if(!auth.active()) {
            throw new PgException(ErrorCode.FORBIDDEN_MERCHANT);
        }

        return auth.mId();
    }

    private String extractSecretKey(String header) {
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            throw new PgException(ErrorCode.UNAUTHORIZED_KEY, "Authorization 헤더가 없거나 Bearer 형식이 아닙니다.");
        }
        String secretKey = header.substring(BEARER_PREFIX.length()).trim();
        if (secretKey.isBlank()) {
            throw new PgException(ErrorCode.UNAUTHORIZED_KEY, "시크릿 키가 비어 있습니다.");
        }
        return secretKey;
    }
}
