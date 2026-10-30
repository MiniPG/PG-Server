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
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class MerchantAuthTest {

    @Autowired MockMvc mockMvc;
    @Autowired MerchantRepository merchantRepository;
    @Autowired TestMerchants merchants;

    @Test
    @DisplayName("Authorization 헤더가 없으면 401")
    void noHeader() throws Exception {
        mockMvc.perform(get("/v1/payments/{key}", "pk_any"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED_KEY"));
    }

    @Test
    @DisplayName("Bearer 형식이 아니면 401")
    void notBearer() throws Exception {
        mockMvc.perform(get("/v1/payments/{key}", "pk_any").header(HttpHeaders.AUTHORIZATION, "Basic abc"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Bearer 뒤에 키가 비어 있으면 401")
    void emptyKey() throws Exception {
        mockMvc.perform(get("/v1/payments/{key}", "pk_any").header(HttpHeaders.AUTHORIZATION, "Bearer   "))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("등록되지 않은 키면 401")
    void unknownKey() throws Exception {
        mockMvc.perform(get("/v1/payments/{key}", "pk_any")
                        .header(HttpHeaders.AUTHORIZATION, TestMerchants.bearer("test_sk_unknown")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("정지된 가맹점이면 403")
    void suspended() throws Exception {
        String key = "test_sk_suspended_" + UUID.randomUUID();
        Merchant merchant = Merchant.create("m_suspend", "정지 가맹점", SecretKeyHasher.hash(key), LocalDateTime.now());
        merchant.suspend();
        merchantRepository.save(merchant);

        mockMvc.perform(get("/v1/payments/{key}", "pk_any").header(HttpHeaders.AUTHORIZATION, TestMerchants.bearer(key)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_MERCHANT"));
    }

    @Test
    @DisplayName("올바른 키면 키에 연결된 가맹점으로 처리된다 (X-Merchant-Id로 다른 가맹점을 사칭해도 무시)")
    void validKeyIgnoresSpoofHeader() throws Exception {
        String auth = merchants.authorization("m_real");

        mockMvc.perform(post("/v1/payments")
                        .header(HttpHeaders.AUTHORIZATION, auth)
                        .header("X-Merchant-Id", "m_victim")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderId":"order-%s","orderName":"티셔츠","amount":10000}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mId").value("m_real"));
    }
}
