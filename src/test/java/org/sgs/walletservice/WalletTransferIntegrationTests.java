package org.sgs.walletservice;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sgs.walletservice.domain.TransferStatus;
import org.sgs.walletservice.domain.Wallet;
import org.sgs.walletservice.dto.TransferRequest;
import org.sgs.walletservice.repo.IdempotencyRecordRepository;
import org.sgs.walletservice.repo.TransferRepository;
import org.sgs.walletservice.repo.WalletRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
public class WalletTransferIntegrationTests {

    /** Global service context root ({@code wallet.api.base-path}); also the security boundary. */
    private static final String BASE_PATH = "/wallet-service";

    /** Versioned API prefix: base path + the version declared on the controllers. */
    private static final String API = BASE_PATH + "/v1";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private org.sgs.walletservice.auth.BearerTokenFilter bearerTokenFilter;

    @Autowired
    private org.sgs.walletservice.logging.CorrelationIdFilter correlationIdFilter;

    @Autowired
    private WalletRepository walletRepository;

    @Autowired
    private TransferRepository transferRepository;

    @Autowired
    private IdempotencyRecordRepository idempotencyRecordRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private io.micrometer.core.instrument.MeterRegistry meterRegistry;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilter(correlationIdFilter)
                .addFilter(bearerTokenFilter)
                .build();
        idempotencyRecordRepository.deleteAll();
        transferRepository.deleteAll();
        walletRepository.deleteAll();

        // Seed fresh wallets
        walletRepository.save(new Wallet("alice", 100_000L)); // 1000 INR
        walletRepository.save(new Wallet("bob", 20_000L));    // 200 INR
    }

    @Test
    void shouldRequireBearerToken() throws Exception {
        mockMvc.perform(post(API + "/wallets"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get(API + "/wallets/1"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post(API + "/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * The security boundary is the service base path, not a hardcoded list of versioned resources.
     * A future /v2 (or any new resource) must be authenticated without touching BearerTokenFilter;
     * 401 rather than 404 proves the filter rejected the request before routing.
     */
    @Test
    void shouldAuthenticateEveryPathUnderTheBasePathRegardlessOfVersion() throws Exception {
        mockMvc.perform(get(BASE_PATH + "/v2/transfers/1"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get(BASE_PATH + "/v1/some-future-resource"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldReturnGeneratedCorrelationIdWhenNoneSupplied() throws Exception {
        mockMvc.perform(post(API + "/wallets")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer tok-alice"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Correlation-Id"))
                .andExpect(header().string("X-Correlation-Id", not(blankOrNullString())));
    }

    @Test
    void shouldEchoSuppliedCorrelationId() throws Exception {
        mockMvc.perform(post(API + "/wallets")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer tok-alice")
                        .header("X-Correlation-Id", "trace-abc-123"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Correlation-Id", is("trace-abc-123")));
    }

    @Test
    void shouldGetOrCreateWallet() throws Exception {
        // tok-alice resolves to alice
        mockMvc.perform(post(API + "/wallets")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer tok-alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.owner_id", is("alice")))
                .andExpect(jsonPath("$.balance_paise", is(100_000)));

        // tok-charlie does not exist, so a new wallet is created with 0 paise
        mockMvc.perform(post(API + "/wallets")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer tok-charlie"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.owner_id", is("charlie")))
                .andExpect(jsonPath("$.balance_paise", is(0)));

        // Test POST /wallets/ with trailing slash
        mockMvc.perform(post(API + "/wallets/")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer tok-david"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.owner_id", is("david")))
                .andExpect(jsonPath("$.balance_paise", is(0)));
    }

    @Test
    void shouldSupportInitialBalanceOnWalletCreation() throws Exception {
        // Create wallet with explicit initial balance in paise
        mockMvc.perform(post(API + "/wallets")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer tok-eve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"initial_balance_paise\": 75000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.owner_id", is("eve")))
                .andExpect(jsonPath("$.balance_paise", is(75_000)));

        // Subsequent getOrCreate does not overwrite the balance
        mockMvc.perform(post(API + "/wallets")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer tok-eve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"initial_balance_paise\": 10000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.owner_id", is("eve")))
                .andExpect(jsonPath("$.balance_paise", is(75_000)));

        // Negative initial balance should be rejected with 400 Bad Request
        mockMvc.perform(post(API + "/wallets")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer tok-frank")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"initial_balance_paise\": -100}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldGetWalletBalanceForOwnerAndForbidNonOwner() throws Exception {
        Wallet aliceWallet = walletRepository.findByOwnerId("alice").orElseThrow();
        Wallet bobWallet = walletRepository.findByOwnerId("bob").orElseThrow();

        // Alice views her own wallet
        mockMvc.perform(get(API + "/wallets/" + aliceWallet.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer tok-alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(aliceWallet.getId().intValue())))
                .andExpect(jsonPath("$.balance_paise", is(100_000)));

        // Alice tries to view Bob's wallet -> 403 Forbidden
        mockMvc.perform(get(API + "/wallets/" + bobWallet.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer tok-alice"))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldExecutePeerToPeerTransferSuccessfully() throws Exception {
        Wallet aliceWallet = walletRepository.findByOwnerId("alice").orElseThrow();
        Wallet bobWallet = walletRepository.findByOwnerId("bob").orElseThrow();

        TransferRequest request = new TransferRequest(
                aliceWallet.getId(),
                bobWallet.getId(),
                25_000L,
                "tx-key-101"
        );

        mockMvc.perform(post(API + "/transfers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer tok-alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.status", is(TransferStatus.COMPLETED.name())))
                .andExpect(jsonPath("$.amount_paise", is(25_000)))
                .andExpect(jsonPath("$.from", is(aliceWallet.getId().intValue())))
                .andExpect(jsonPath("$.to", is(bobWallet.getId().intValue())));

        // Verify balances
        Wallet updatedAlice = walletRepository.findById(aliceWallet.getId()).orElseThrow();
        Wallet updatedBob = walletRepository.findById(bobWallet.getId()).orElseThrow();
        assertEquals(75_000L, updatedAlice.getBalancePaise());
        assertEquals(45_000L, updatedBob.getBalancePaise());
    }

    @Test
    void shouldEnforceIdempotencyOnTransfers() throws Exception {
        Wallet aliceWallet = walletRepository.findByOwnerId("alice").orElseThrow();
        Wallet bobWallet = walletRepository.findByOwnerId("bob").orElseThrow();

        TransferRequest request = new TransferRequest(
                aliceWallet.getId(),
                bobWallet.getId(),
                10_000L,
                "idemp-key-repeat"
        );

        // First transfer call
        String firstResponse = mockMvc.perform(post(API + "/transfers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer tok-alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        Long firstTransferId = objectMapper.readTree(firstResponse).get("id").asLong();

        // Second transfer call with the exact same idempotency key
        String secondResponse = mockMvc.perform(post(API + "/transfers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer tok-alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        Long secondTransferId = objectMapper.readTree(secondResponse).get("id").asLong();

        assertEquals(firstTransferId, secondTransferId);

        // Alice should only have been debited 10,000 paise ONCE
        Wallet updatedAlice = walletRepository.findById(aliceWallet.getId()).orElseThrow();
        Wallet updatedBob = walletRepository.findById(bobWallet.getId()).orElseThrow();
        assertEquals(90_000L, updatedAlice.getBalancePaise());
        assertEquals(30_000L, updatedBob.getBalancePaise());
    }

    @Test
    void shouldRejectTransferWhenInsufficientBalance() throws Exception {
        Wallet aliceWallet = walletRepository.findByOwnerId("alice").orElseThrow();
        Wallet bobWallet = walletRepository.findByOwnerId("bob").orElseThrow();

        TransferRequest request = new TransferRequest(
                aliceWallet.getId(),
                bobWallet.getId(),
                999_999L, // Alice only has 100_000
                "tx-insufficient"
        );

        mockMvc.perform(post(API + "/transfers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer tok-alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnprocessableEntity());

        // Alice's balance must be untouched
        Wallet alice = walletRepository.findById(aliceWallet.getId()).orElseThrow();
        assertEquals(100_000L, alice.getBalancePaise());
    }

    @Test
    void shouldRejectReusedIdempotencyKeyWithDifferentBody() throws Exception {
        Wallet aliceWallet = walletRepository.findByOwnerId("alice").orElseThrow();
        Wallet bobWallet = walletRepository.findByOwnerId("bob").orElseThrow();

        // First attempt fails due to insufficient funds
        TransferRequest tooBig = new TransferRequest(
                aliceWallet.getId(), bobWallet.getId(), 999_999L, "reused-key");

        mockMvc.perform(post(API + "/transfers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer tok-alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(tooBig)))
                .andExpect(status().isUnprocessableEntity());

        // Same key, smaller amount -> conflict, NOT a new debit
        TransferRequest smaller = new TransferRequest(
                aliceWallet.getId(), bobWallet.getId(), 1_000L, "reused-key");

        mockMvc.perform(post(API + "/transfers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer tok-alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(smaller)))
                .andExpect(status().isConflict());

        // Same key, identical body -> original failure is replayed
        mockMvc.perform(post(API + "/transfers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer tok-alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(tooBig)))
                .andExpect(status().isUnprocessableEntity());

        // Balances untouched
        assertEquals(100_000L, walletRepository.findById(aliceWallet.getId()).orElseThrow().getBalancePaise());
        assertEquals(20_000L, walletRepository.findById(bobWallet.getId()).orElseThrow().getBalancePaise());
    }

    @Test
    void shouldRejectIdempotencyKeyReusedByAnotherCaller() throws Exception {
        Wallet aliceWallet = walletRepository.findByOwnerId("alice").orElseThrow();
        Wallet bobWallet = walletRepository.findByOwnerId("bob").orElseThrow();

        // Alice claims the key
        TransferRequest aliceRequest = new TransferRequest(
                aliceWallet.getId(), bobWallet.getId(), 5_000L, "shared-key");

        mockMvc.perform(post(API + "/transfers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer tok-alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(aliceRequest)))
                .andExpect(status().isCreated());

        // Bob tries the same key for his own transfer -> conflict, no transfer performed
        TransferRequest bobRequest = new TransferRequest(
                bobWallet.getId(), aliceWallet.getId(), 1_000L, "shared-key");

        mockMvc.perform(post(API + "/transfers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer tok-bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bobRequest)))
                .andExpect(status().isConflict());

        // Only Alice's 5,000 paise moved
        assertEquals(95_000L, walletRepository.findById(aliceWallet.getId()).orElseThrow().getBalancePaise());
        assertEquals(25_000L, walletRepository.findById(bobWallet.getId()).orElseThrow().getBalancePaise());
    }

    @Test
    void shouldRecordDomainCounters() throws Exception {
        Wallet aliceWallet = walletRepository.findByOwnerId("alice").orElseThrow();
        Wallet bobWallet = walletRepository.findByOwnerId("bob").orElseThrow();

        double createdBefore = counter("wallet.transfers.created", null);
        double declinedBefore = counter("wallet.transfers.declined", "insufficient_funds");
        double replaysBefore = counter("wallet.transfers.idempotent_replays", null);

        // 1. A successful transfer
        TransferRequest ok = new TransferRequest(
                aliceWallet.getId(), bobWallet.getId(), 1_000L, "metrics-ok");
        mockMvc.perform(post(API + "/transfers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer tok-alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ok)))
                .andExpect(status().isCreated());

        // 2. Replaying the same key
        mockMvc.perform(post(API + "/transfers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer tok-alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ok)))
                .andExpect(status().isCreated());

        // 3. A transfer that cannot be funded
        TransferRequest broke = new TransferRequest(
                aliceWallet.getId(), bobWallet.getId(), 9_999_999L, "metrics-broke");
        mockMvc.perform(post(API + "/transfers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer tok-alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(broke)))
                .andExpect(status().isUnprocessableEntity());

        assertEquals(createdBefore + 1, counter("wallet.transfers.created", null));
        assertEquals(declinedBefore + 1, counter("wallet.transfers.declined", "insufficient_funds"));
        assertEquals(replaysBefore + 1, counter("wallet.transfers.idempotent_replays", null));
    }

    private double counter(String name, String reasonTag) {
        var search = meterRegistry.find(name);
        if (reasonTag != null) {
            search = search.tag("reason", reasonTag);
        }
        io.micrometer.core.instrument.Counter counter = search.counter();
        return (counter != null) ? counter.count() : 0d;
    }

    @Test
    void shouldForbidTransferFromWalletNotOwnedByCaller() throws Exception {
        Wallet aliceWallet = walletRepository.findByOwnerId("alice").orElseThrow();
        Wallet bobWallet = walletRepository.findByOwnerId("bob").orElseThrow();

        // Bob tries to initiate a transfer from Alice's wallet
        TransferRequest request = new TransferRequest(
                aliceWallet.getId(),
                bobWallet.getId(),
                5_000L,
                "tx-unauthorized-sender"
        );

        mockMvc.perform(post(API + "/transfers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer tok-bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldRejectTransferToSameWalletOrNonPositiveAmount() throws Exception {
        Wallet aliceWallet = walletRepository.findByOwnerId("alice").orElseThrow();

        // Same wallet
        TransferRequest sameWalletRequest = new TransferRequest(
                aliceWallet.getId(),
                aliceWallet.getId(),
                1_000L,
                "tx-same-wallet"
        );
        mockMvc.perform(post(API + "/transfers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer tok-alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sameWalletRequest)))
                .andExpect(status().isBadRequest());

        // Negative or zero amount
        TransferRequest nonPositiveRequest = new TransferRequest(
                aliceWallet.getId(),
                999L,
                0L,
                "tx-zero-amount"
        );
        mockMvc.perform(post(API + "/transfers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer tok-alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(nonPositiveRequest)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldGetTransferStatusById() throws Exception {
        Wallet aliceWallet = walletRepository.findByOwnerId("alice").orElseThrow();
        Wallet bobWallet = walletRepository.findByOwnerId("bob").orElseThrow();

        TransferRequest request = new TransferRequest(
                aliceWallet.getId(),
                bobWallet.getId(),
                15_000L,
                "tx-status-check"
        );

        String createResponse = mockMvc.perform(post(API + "/transfers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer tok-alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        Long transferId = objectMapper.readTree(createResponse).get("id").asLong();

        // Query status with Alice
        mockMvc.perform(get(API + "/transfers/" + transferId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer tok-alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(transferId.intValue())))
                .andExpect(jsonPath("$.status", is("COMPLETED")))
                .andExpect(jsonPath("$.amount_paise", is(15_000)));

        // Query status with Bob (recipient)
        mockMvc.perform(get(API + "/transfers/" + transferId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer tok-bob"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(transferId.intValue())))
                .andExpect(jsonPath("$.status", is("COMPLETED")));

        // Unrelated user cannot view transfer
        mockMvc.perform(get(API + "/transfers/" + transferId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer tok-carol"))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldHandleConcurrentOpposingTransfersWithoutDeadlock() throws Exception {
        Wallet aliceWallet = walletRepository.findByOwnerId("alice").orElseThrow();
        Wallet bobWallet = walletRepository.findByOwnerId("bob").orElseThrow();

        int threads = 10;
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    if (index % 2 == 0) {
                        TransferRequest req = new TransferRequest(aliceWallet.getId(), bobWallet.getId(), 100L, "concurrent-alice-" + index);
                        mockMvc.perform(post(API + "/transfers")
                                        .header(HttpHeaders.AUTHORIZATION, "Bearer tok-alice")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(req)))
                                .andExpect(status().isCreated());
                    } else {
                        TransferRequest req = new TransferRequest(bobWallet.getId(), aliceWallet.getId(), 100L, "concurrent-bob-" + index);
                        mockMvc.perform(post(API + "/transfers")
                                        .header(HttpHeaders.AUTHORIZATION, "Bearer tok-bob")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(req)))
                                .andExpect(status().isCreated());
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    latch.countDown();
                }
            });
        }

        boolean finished = latch.await(15, java.util.concurrent.TimeUnit.SECONDS);
        executor.shutdown();

        org.junit.jupiter.api.Assertions.assertTrue(finished, "Transfers should complete within timeout without deadlock");

        Wallet updatedAlice = walletRepository.findById(aliceWallet.getId()).orElseThrow();
        Wallet updatedBob = walletRepository.findById(bobWallet.getId()).orElseThrow();

        // 5 transfers of 100 from Alice to Bob, 5 transfers of 100 from Bob to Alice => net 0 change, total preserved
        assertEquals(100_000L, updatedAlice.getBalancePaise());
        assertEquals(20_000L, updatedBob.getBalancePaise());
        assertEquals(120_000L, updatedAlice.getBalancePaise() + updatedBob.getBalancePaise());
    }
}
