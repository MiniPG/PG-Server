package com.sangyunpark99.pgserver.common.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

@SpringBootTest
@AutoConfigureMockMvc
class RequestIdFilterTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("요청 ID가 없으면 새로 만들어 응답 헤더에 넣는다 (인증 실패 응답에도)")
    void generate() throws Exception {
        mockMvc.perform(get("/v1/payments/{key}", "pk_any"))
                .andExpect(header().string(RequestIdFilter.HEADER, matchesPattern("[a-f0-9]{32}")));
    }

    @Test
    @DisplayName("가맹점이 보낸 요청 ID는 그대로 이어서 쓴다")
    void propagate() throws Exception {
        mockMvc.perform(get("/v1/payments/{key}", "pk_any").header(RequestIdFilter.HEADER, "merchant-req-123"))
                .andExpect(header().string(RequestIdFilter.HEADER, "merchant-req-123"));
    }

    @Test
    @DisplayName("형식이 이상한 요청 ID는 무시하고 새로 만든다 (로그 주입 방지)")
    void rejectInvalid() throws Exception {
        mockMvc.perform(get("/v1/payments/{key}", "pk_any").header(RequestIdFilter.HEADER, "bad id\nINJECT"))
                .andExpect(header().string(RequestIdFilter.HEADER, matchesPattern("[a-f0-9]{32}")));
    }
}
