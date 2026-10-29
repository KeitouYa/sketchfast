package io.github.keitouya.aiimage.common;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.keitouya.aiimage.TestcontainersConfiguration;
import io.github.keitouya.aiimage.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Auth, error format and request ids, the parts every endpoint relies on. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, ApiBasicsTest.ProbeController.class })
class ApiBasicsTest {

	@Autowired
	MockMvc mvc;

	@Test
	void healthIsPublic() throws Exception {
		mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
	}

	@Test
	void missingTokenGetsProblemDetailsWithCodeAndRequestId() throws Exception {
		mvc.perform(get("/api/probe/me"))
			.andExpect(status().isUnauthorized())
			.andExpect(header().string("Content-Type", MediaType.APPLICATION_PROBLEM_JSON_VALUE))
			.andExpect(header().string("WWW-Authenticate", "Bearer"))
			.andExpect(header().exists(RequestIdFilter.HEADER))
			.andExpect(jsonPath("$.status").value(401))
			.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
			.andExpect(jsonPath("$.requestId").isNotEmpty());
	}

	@Test
	void tokenTheDecoderRejectsIs401NotServerError() throws Exception {
		mvc.perform(get("/api/probe/me").header("Authorization", "Bearer not.a.jwt"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
	}

	@Test
	void callerRequestIdIsKeptInHeaderAndBody() throws Exception {
		mvc.perform(get("/api/probe/me").header(RequestIdFilter.HEADER, "trace-123"))
			.andExpect(header().string(RequestIdFilter.HEADER, "trace-123"))
			.andExpect(jsonPath("$.requestId").value("trace-123"));
	}

	@Test
	void malformedRequestIdIsReplaced() throws Exception {
		mvc.perform(get("/api/probe/me").header(RequestIdFilter.HEADER, "bad id <script>"))
			.andExpect(header().string(RequestIdFilter.HEADER, org.hamcrest.Matchers.matchesPattern("[a-f0-9]{16}")));
	}

	@Test
	void signedInUserIsReadFromTokenClaims() throws Exception {
		mvc.perform(get("/api/probe/me").with(jwt().jwt(j -> j.claim("email", " Ada@Example.com ").claim("name", "Ada"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.email").value("ada@example.com"))
			.andExpect(jsonPath("$.name").value("Ada"));
	}

	@Test
	void tokenWithoutEmailClaimIsRejected() throws Exception {
		mvc.perform(get("/api/probe/me").with(jwt()))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
			.andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("email claim")));
	}

	@Test
	void unknownEndpointIsNotFound() throws Exception {
		mvc.perform(get("/api/nothing-here").with(jwt().jwt(j -> j.claim("email", "a@b.c"))))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("NOT_FOUND"))
			.andExpect(jsonPath("$.requestId").isNotEmpty());
	}

	@Test
	void invalidBodyListsTheFields() throws Exception {
		mvc.perform(post("/api/probe/echo").with(jwt().jwt(j -> j.claim("email", "a@b.c")))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"text\":\"\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.errors[0].field").value("text"));
	}

	@Test
	void apiExceptionKeepsItsCode() throws Exception {
		mvc.perform(get("/api/probe/broke").with(jwt().jwt(j -> j.claim("email", "a@b.c"))))
			.andExpect(status().isPaymentRequired())
			.andExpect(jsonPath("$.code").value("INSUFFICIENT_CREDITS"))
			.andExpect(jsonPath("$.title").value("Insufficient credits"));
	}

	@RestController
	static class ProbeController {

		record Echo(@Size(min = 1, max = 5) String text) {
		}

		@GetMapping("/api/probe/me")
		CurrentUser me(CurrentUser user) {
			return user;
		}

		@PostMapping("/api/probe/echo")
		Echo echo(@Valid @RequestBody Echo body) {
			return body;
		}

		@GetMapping("/api/probe/broke")
		void broke() {
			throw new ApiException(ErrorCode.INSUFFICIENT_CREDITS, "You have 0 credits.");
		}
	}
}
