package com.sangyunpark99.pgserver.payment.application.reconciliation;

import com.jayway.jsonpath.JsonPath;
import com.sangyunpark99.pgserver.card.CardApprovalResult;
import com.sangyunpark99.pgserver.card.CardCancelResult;
import com.sangyunpark99.pgserver.card.CardCompanyClient;
import com.sangyunpark99.pgserver.common.exception.CardCompanyUnavailableException;
import com.sangyunpark99.pgserver.payment.domain.Payment;
import com.sangyunpark99.pgserver.payment.domain.PaymentStatus;
import com.sangyunpark99.pgserver.payment.domain.PaymentTransaction;
import com.sangyunpark99.pgserver.payment.domain.TransactionStatus;
import com.sangyunpark99.pgserver.payment.domain.TransactionType;
import com.sangyunpark99.pgserver.payment.infrastructure.PaymentRepository;
import com.sangyunpark99.pgserver.payment.infrastructure.PaymentTransactionRepository;
import com.sangyunpark99.pgserver.support.TestMerchants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest
@AutoConfigureMockMvc
class ReconciliationServiceTest {
    private static final String MERCHANT = "m_reconcile";

    @Autowired
    private ReconciliationService reconciliationService;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestMerchants merchants;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private PaymentTransactionRepository transactionRepository;

    @MockitoBean
    private CardCompanyClient cardCompanyClient;

    @BeforeEach
    void setUp() {
        given(cardCompanyClient.approve(anyString(), anyString(), anyLong()))
                .willReturn(CardApprovalResult.approved("12345678", "11", "1234-****-****-5678"));
        given(cardCompanyClient.cancel(anyString(), anyString(), anyLong()))
                .willReturn(CardCancelResult.success());
    }

    @Test
    @DisplayName("결과 모름으로 남은 승인 거래는 같은 키로 망취소해 결제 ABORTED, 거래 FAILED로 확정한다")
    void reverseUnknownApproval() throws Exception {
        String paymentKey = unknownApproval(15_000);
        PaymentTransaction tx = approveTx(paymentKey);
        willDoNothing().given(cardCompanyClient).reverse(anyString(), anyString(), anyLong());

        reconciliationService.reconcile(later());

        verify(cardCompanyClient).reverse(tx.getTransactionKey(), paymentKey, 15_000);
        Payment payment = paymentRepository.findByPaymentKey(paymentKey).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.ABORTED);
        assertThat(payment.getFailureCode()).isEqualTo("APPROVAL_REVERSED");
        assertThat(approveTx(paymentKey).getStatus()).isEqualTo(TransactionStatus.FAILED);
        assertThat(approveTx(paymentKey).getCompletedAt()).isNotNull();
    }

    @Test
    @DisplayName("망취소도 응답이 없으면 그대로 두고 다음 실행에서 다시 시도한다")
    void keepWhenReversalUnavailable() throws Exception {
        String paymentKey = unknownApproval(15_000);

        reconciliationService.reconcile(later());

        assertThat(paymentRepository.findByPaymentKey(paymentKey).orElseThrow().getStatus()).isEqualTo(PaymentStatus.APPROVING);
        assertThat(approveTx(paymentKey).getStatus()).isEqualTo(TransactionStatus.REQUESTED);

        willDoNothing().given(cardCompanyClient).reverse(anyString(), anyString(), anyLong());
        reconciliationService.reconcile(later());

        assertThat(paymentRepository.findByPaymentKey(paymentKey).orElseThrow().getStatus()).isEqualTo(PaymentStatus.ABORTED);
    }

    @Test
    @DisplayName("결과 모름으로 남은 취소 거래는 같은 키로 다시 취소해 성공하면 거래 DONE, 잔액은 차감된 채로 둔다")
    void retryUnknownCancel() throws Exception {
        String paymentKey = unknownCancel(10_000, 3_000);
        PaymentTransaction tx = cancelTx(paymentKey);

        reconciliationService.reconcile(later());

        verify(cardCompanyClient).cancel(eq(tx.getTransactionKey()), anyString(), eq(3_000L));
        assertThat(cancelTx(paymentKey).getStatus()).isEqualTo(TransactionStatus.DONE);
        Payment payment = paymentRepository.findByPaymentKey(paymentKey).orElseThrow();
        assertThat(payment.getBalanceAmount()).isEqualTo(7_000);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PARTIAL_CANCELED);
    }

    @Test
    @DisplayName("다시 보낸 취소를 카드사가 거절하면 잔액을 되돌리고 거래 FAILED")
    void restoreWhenCancelRejected() throws Exception {
        String paymentKey = unknownCancel(10_000, 3_000);
        willReturn(CardCancelResult.rejected("REJECT_CANCEL", "거절"))
                .given(cardCompanyClient).cancel(anyString(), anyString(), anyLong());

        reconciliationService.reconcile(later());

        assertThat(cancelTx(paymentKey).getStatus()).isEqualTo(TransactionStatus.FAILED);
        Payment payment = paymentRepository.findByPaymentKey(paymentKey).orElseThrow();
        assertThat(payment.getBalanceAmount()).isEqualTo(10_000);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.DONE);
    }

    @Test
    @DisplayName("기준 시간이 지나지 않은 거래는 처리 중인 요청일 수 있어 건드리지 않는다")
    void skipRecentTransaction() throws Exception {
        String paymentKey = unknownApproval(15_000);
        willDoNothing().given(cardCompanyClient).reverse(anyString(), anyString(), anyLong());

        reconciliationService.reconcile(LocalDateTime.now());

        verify(cardCompanyClient, never()).reverse(eq(approveTx(paymentKey).getTransactionKey()), anyString(), anyLong());
        assertThat(approveTx(paymentKey).getStatus()).isEqualTo(TransactionStatus.REQUESTED);
    }

    @Test
    @DisplayName("두 서버가 동시에 같은 취소 거래를 확정해도 잔액은 한 번만 되돌린다")
    void restoreOnceWhenReconciledConcurrently() throws Exception {
        String paymentKey = unknownCancel(10_000, 3_000);
        willReturn(CardCancelResult.rejected("REJECT_CANCEL", "거절"))
                .given(cardCompanyClient).cancel(anyString(), anyString(), anyLong());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        LocalDateTime now = later();
        for (int i = 0; i < 2; i++) {
            executor.submit(() -> {
                start.await();
                return reconciliationService.reconcile(now);
            });
        }
        start.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        Payment payment = paymentRepository.findByPaymentKey(paymentKey).orElseThrow();
        System.out.printf("[동시 확정] 잔액 %d, 상태 %s, 거래 %s%n",
                payment.getBalanceAmount(), payment.getStatus(), cancelTx(paymentKey).getStatus());
        assertThat(payment.getBalanceAmount()).isEqualTo(10_000);
        assertThat(cancelTx(paymentKey).getStatus()).isEqualTo(TransactionStatus.FAILED);
    }

    private String unknownApproval(long amount) throws Exception {
        willThrow(new CardCompanyUnavailableException("timeout", null))
                .given(cardCompanyClient).approve(anyString(), anyString(), anyLong());
        willThrow(new CardCompanyUnavailableException("timeout", null))
                .given(cardCompanyClient).reverse(anyString(), anyString(), anyLong());
        String orderId = newOrderId();
        String paymentKey = create(orderId, amount);
        confirm(paymentKey, orderId, amount);
        clearInvocations(cardCompanyClient);
        return paymentKey;
    }

    private String unknownCancel(long amount, long cancelAmount) throws Exception {
        String orderId = newOrderId();
        String paymentKey = create(orderId, amount);
        confirm(paymentKey, orderId, amount);
        willThrow(new CardCompanyUnavailableException("timeout", null))
                .given(cardCompanyClient).cancel(anyString(), anyString(), anyLong());
        mockMvc.perform(post("/v1/payments/{paymentKey}/cancel", paymentKey)
                .header(HttpHeaders.AUTHORIZATION, merchants.authorization(MERCHANT))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"cancelReason":"반품","cancelAmount":%d}
                        """.formatted(cancelAmount)));
        willReturn(CardCancelResult.success())
                .given(cardCompanyClient).cancel(anyString(), anyString(), anyLong());
        clearInvocations(cardCompanyClient);
        return paymentKey;
    }

    private String create(String orderId, long amount) throws Exception {
        String response = mockMvc.perform(post("/v1/payments")
                        .header(HttpHeaders.AUTHORIZATION, merchants.authorization(MERCHANT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderId":"%s","orderName":"티셔츠","amount":%d,"partialCancelable":true}
                                """.formatted(orderId, amount)))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.paymentKey");
    }

    private void confirm(String paymentKey, String orderId, long amount) throws Exception {
        mockMvc.perform(post("/v1/payments/confirm")
                .header(HttpHeaders.AUTHORIZATION, merchants.authorization(MERCHANT))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"paymentKey":"%s","orderId":"%s","amount":%d}
                        """.formatted(paymentKey, orderId, amount)));
    }

    private PaymentTransaction approveTx(String paymentKey) {
        return transactionRepository.findByPaymentKeyAndTypeOrderByIdAsc(paymentKey, TransactionType.APPROVE).getFirst();
    }

    private PaymentTransaction cancelTx(String paymentKey) {
        return transactionRepository.findByPaymentKeyAndTypeOrderByIdAsc(paymentKey, TransactionType.CANCEL).getFirst();
    }

    private LocalDateTime later() {
        return LocalDateTime.now().plusMinutes(1);
    }

    private String newOrderId() {
        return "order-" + UUID.randomUUID();
    }
}
