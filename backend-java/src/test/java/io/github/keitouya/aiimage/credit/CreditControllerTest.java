package io.github.keitouya.aiimage.credit;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import io.github.keitouya.aiimage.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CreditControllerTest {

	@Autowired
	MockMvc mvc;

	private final RequestPostProcessor user = jwt().jwt(j -> j.claim("email", "c-" + UUID.randomUUID() + "@test.dev"));

	@Test
	void packagesArePublicAndPricedInCents() throws Exception {
		mvc.perform(get("/api/credit-packages"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(3))
			.andExpect(jsonPath("$[1].id").value("credits_30"))
			.andExpect(jsonPath("$[1].priceCents").value(250))
			.andExpect(jsonPath("$[1].currency").value("usd"));
	}

	@Test
	void newUserSeesTheSignupBonus() throws Exception {
		mvc.perform(get("/api/me/credits").with(user))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.balance").value(20));
	}

	@Test
	void ledgerListsTheSignupBonus() throws Exception {
		mvc.perform(get("/api/me/credit-ledger").with(user))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].type").value("SIGNUP_BONUS"))
			.andExpect(jsonPath("$.items[0].delta").value(20))
			.andExpect(jsonPath("$.page").value(1))
			.andExpect(jsonPath("$.totalPages").value(1));
	}

	@Test
	void outOfRangePageIsAValidationError() throws Exception {
		mvc.perform(get("/api/me/credit-ledger?size=500").with(user))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

	@Test
	void balanceNeedsSignIn() throws Exception {
		mvc.perform(get("/api/me/credits")).andExpect(status().isUnauthorized());
	}
}
