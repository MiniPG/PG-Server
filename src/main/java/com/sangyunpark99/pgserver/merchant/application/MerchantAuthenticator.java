package com.sangyunpark99.pgserver.merchant.application;

import com.sangyunpark99.pgserver.common.exception.ErrorCode;
import com.sangyunpark99.pgserver.common.exception.PgException;
import com.sangyunpark99.pgserver.merchant.domain.Merchant;
import com.sangyunpark99.pgserver.merchant.infrastructure.MerchantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class MerchantAuthenticator {
    private static final String BEARER_PREFIX = "Bearer ";

    private final MerchantRepository merchantRepository;

    @Transactional(readOnly = true)
    public String authenticate(String authorizationHeader) {
        String secretKey = extractSecretKey(authorizationHeader);

        Merchant merchant = merchantRepository.findBySecretKeyHash(SecretKeyHasher.hash(secretKey))
                .orElseThrow(() -> new PgException(ErrorCode.UNAUTHORIZED_KEY));

        if (!merchant.isActive()) {
            throw new PgException(ErrorCode.FORBIDDEN_MERCHANT);
        }
        return merchant.getMId();
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
