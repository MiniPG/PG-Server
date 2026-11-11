package com.sangyunpark99.pgserver.merchant;

import com.sangyunpark99.pgserver.merchant.application.SecretKeyHasher;
import com.sangyunpark99.pgserver.merchant.domain.Merchant;
import com.sangyunpark99.pgserver.merchant.infrastructure.MerchantRepository;
import com.sangyunpark99.pgserver.support.TestMerchants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "pg.merchant-auth.cache-ttl=300ms")
class MerchantAuthCacheTest {

    @Autowired MockMvc mockMvc;
    @MockitoSpyBean MerchantRepository merchantRepository;

    @Test
    @DisplayName("조회는 같은 키로 두 번 요청해도 가맹점을 DB에서 한 번만 읽는다")
    void getUsesCache() throws Exception {
        String key = newMerchant();

        getPayment(key);
        getPayment(key);

        verify(merchantRepository, times(1)).findBySecretKeyHash(SecretKeyHasher.hash(key));
    }

    @Test
    @DisplayName("승인은 캐시에 있어도 매번 DB에서 가맹점을 읽는다")
    void confirmAlwaysReadsDb() throws Exception {
        String key = newMerchant();
        getPayment(key);
        clearInvocations(merchantRepository);

        confirm(key);
        confirm(key);

        verify(merchantRepository, times(2)).findBySecretKeyHash(SecretKeyHasher.hash(key));
    }

    @Test
    @DisplayName("캐시에 활성으로 남아 있어도 정지된 가맹점의 승인은 바로 403")
    void suspendedMerchantCannotConfirmEvenIfCached() throws Exception {
        String key = newMerchant();
        getPayment(key);

        Merchant merchant = merchantRepository.findBySecretKeyHash(SecretKeyHasher.hash(key)).orElseThrow();
        merchant.suspend();
        merchantRepository.save(merchant);

        mockMvc.perform(post("/v1/payments/confirm")
                        .header(HttpHeaders.AUTHORIZATION, TestMerchants.bearer(key))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("TTL이 지나면 조회도 가맹점을 DB에서 다시 읽는다")
    void cacheExpires() throws Exception {
        String key = newMerchant();

        getPayment(key);
        Thread.sleep(500);
        getPayment(key);

        verify(merchantRepository, times(2)).findBySecretKeyHash(SecretKeyHasher.hash(key));
    }

    @Test
    @DisplayName("등록되지 않은 키는 캐시하지 않는다")
    void unknownKeyIsNotCached() throws Exception {
        String key = "test_sk_unknown_" + UUID.randomUUID();

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(get("/v1/payments/{key}", "pk_any")
                            .header(HttpHeaders.AUTHORIZATION, TestMerchants.bearer(key)))
                    .andExpect(status().isUnauthorized());
        }

        verify(merchantRepository, times(2)).findBySecretKeyHash(SecretKeyHasher.hash(key));
    }

    private String newMerchant() {
        String key = "test_sk_cache_" + UUID.randomUUID();
        String mId = "m_" + UUID.randomUUID().toString().substring(0, 10);
        merchantRepository.save(Merchant.create(mId, mId, SecretKeyHasher.hash(key), LocalDateTime.now()));
        clearInvocations(merchantRepository);
        return key;
    }

    private void getPayment(String key) throws Exception {
        mockMvc.perform(get("/v1/payments/{key}", "pk_none")
                        .header(HttpHeaders.AUTHORIZATION, TestMerchants.bearer(key)))
                .andExpect(status().isNotFound());
    }

    private void confirm(String key) throws Exception {
        mockMvc.perform(post("/v1/payments/confirm")
                .header(HttpHeaders.AUTHORIZATION, TestMerchants.bearer(key))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"));
    }
}
