package io.github.keitouya.aiimage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import io.github.keitouya.aiimage.image.Image;
import io.github.keitouya.aiimage.image.ImageService;
import io.github.keitouya.aiimage.image.provider.ImageGenerator;
import io.github.keitouya.aiimage.prompt.EnhanceService;
import io.github.keitouya.aiimage.prompt.PromptPreparer;
import io.github.keitouya.aiimage.security.CurrentUser;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * Calls the real providers once, with the keys in backend-java/.env: Gemini (enhance and translate), Replicate
 * (one image) and Cloudinary (upload from Replicate's URL as WebP). The database is still Testcontainers.
 * Costs about a third of a US cent per run. Run: {@code ./mvnw test -Dgroups=live -Dtest.excluded.groups=}
 */
@Tag("live")
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class LiveProvidersTest {

	@Autowired
	EnhanceService enhance;

	@Autowired
	PromptPreparer preparer;

	@Autowired
	ImageService images;

	@Autowired
	ImageGenerator generator;

	@Autowired
	MongoTemplate mongo;

	@Test
	void enhanceRewritesAndTranslates() {
		String prompt = enhance.enhance("一只在屋顶上晒太阳的猫");
		System.out.println("[live] enhance: " + prompt);
		assertThat(prompt).isNotBlank().doesNotContainPattern("\\p{IsHan}");
	}

	@Test
	void chineseImageDescriptionIsTranslated() {
		String modelPrompt = preparer.forImageModel("一辆红色的摩托车停在海边", "none");
		System.out.println("[live] translated: " + modelPrompt);
		assertThat(modelPrompt.toLowerCase()).contains("motorcycle");
	}

	@Test
	void oneRealImageIsGeneratedAndStored() throws Exception {
		CurrentUser user = new CurrentUser("live-" + UUID.randomUUID() + "@test.dev", "Live");
		String id = images.submit(user, "a red vintage motorcycle parked by the sea at sunset", "photo").id();
		String jobId = image(id).providerJobId();

		Instant deadline = Instant.now().plus(Duration.ofSeconds(120));
		while (!image(id).status().finished() && Instant.now().isBefore(deadline)) {
			Thread.sleep(2000);
			images.onJobUpdate(generator.fetch(jobId));
		}

		Image done = image(id);
		System.out.println("[live] image " + done.status() + " " + done.url() + " failure=" + done.failureCode());
		assertThat(done.status()).isEqualTo(Image.Status.SUCCEEDED);
		assertThat(done.url()).startsWith("https://res.cloudinary.com/").endsWith(".webp");
	}

	private Image image(String id) {
		return mongo.findOne(query(where("_id").is(id)), Image.class);
	}
}
