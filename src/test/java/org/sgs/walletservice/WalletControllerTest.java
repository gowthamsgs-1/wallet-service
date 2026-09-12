package org.sgs.walletservice;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
public class WalletControllerTest {

    @Autowired
    private WebApplicationContext context;

    @Test
    void shouldCreateAndGetWallet() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).build();

        // 1. Create wallet for Alice with default 0 balance
        mockMvc.perform(post("/wallets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"user_id\": \"alice\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.user_id", is("alice")))
                .andExpect(jsonPath("$.balance_paise", is(0)));

        // 2. Create wallet for Bob with initial balance of 50,000 paise
        String bobRes = mockMvc.perform(post("/wallets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"user_id\": \"bob\", \"initial_balance_paise\": 50000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user_id", is("bob")))
                .andExpect(jsonPath("$.balance_paise", is(50000)))
                .andReturn().getResponse().getContentAsString();

        // 3. Repeat for Bob (get-or-create) should return existing wallet and not overwrite balance
        mockMvc.perform(post("/wallets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"user_id\": \"bob\", \"initial_balance_paise\": 100}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user_id", is("bob")))
                .andExpect(jsonPath("$.balance_paise", is(50000)));

        // 4. GET /wallets/{id} for existing wallet
        mockMvc.perform(get("/wallets/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(1)))
                .andExpect(jsonPath("$.user_id", is("alice")))
                .andExpect(jsonPath("$.balance_paise", is(0)));

        // 5. GET /wallets/{id} for non-existent wallet
        mockMvc.perform(get("/wallets/99999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status", is(404)))
                .andExpect(jsonPath("$.error", is("Not Found")));

        // 6. POST /wallets with negative balance -> 400 Bad Request
        mockMvc.perform(post("/wallets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"user_id\": \"charlie\", \"initial_balance_paise\": -500}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)))
                .andExpect(jsonPath("$.error", is("Bad Request")));

        // 7. POST /wallets without user_id -> 400 Bad Request
        mockMvc.perform(post("/wallets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)))
                .andExpect(jsonPath("$.error", is("Bad Request")));
    }
}

