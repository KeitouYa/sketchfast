package io.github.keitouya.aiimage;

import java.time.Duration;
import java.util.List;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.bson.Document;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * One MongoDB 7 single-node replica set for the whole test run (credits and payments use transactions).
 *
 * <p>The replica set is initiated through the Java driver rather than Testcontainers' {@code withReplicaSet()},
 * which runs {@code docker exec} and fails on Docker Desktop for Windows ("the pipe is being closed").
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	@Bean
	DynamicPropertyRegistrar mongoProperties() {
		return registry -> registry.add("spring.mongodb.uri", MongoReplicaSet::uri);
	}

	public static final class MongoReplicaSet {

		private static GenericContainer<?> container;

		private MongoReplicaSet() {
		}

		public static synchronized String uri() {
			if (container == null) {
				container = new GenericContainer<>(DockerImageName.parse("mongo:7"))
					.withCommand("--replSet", "rs0", "--bind_ip_all")
					.withExposedPorts(27017)
					.waitingFor(Wait.forLogMessage(".*Waiting for connections.*", 1));
				container.start();
				initiate("mongodb://" + address() + "/?directConnection=true");
			}
			return "mongodb://" + address() + "/aiimage?directConnection=true";
		}

		private static String address() {
			return container.getHost() + ":" + container.getMappedPort(27017);
		}

		private static void initiate(String directUri) {
			try (MongoClient client = MongoClients.create(directUri)) {
				var admin = client.getDatabase("admin");
				admin.runCommand(new Document("replSetInitiate", new Document("_id", "rs0").append("members",
						List.of(new Document("_id", 0).append("host", "localhost:27017")))));
				long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
				while (!Boolean.TRUE.equals(admin.runCommand(new Document("hello", 1)).getBoolean("isWritablePrimary"))) {
					if (System.nanoTime() > deadline) {
						throw new IllegalStateException("MongoDB replica set did not elect a primary in 30s");
					}
					Thread.sleep(200);
				}
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(ex);
			}
		}
	}
}
