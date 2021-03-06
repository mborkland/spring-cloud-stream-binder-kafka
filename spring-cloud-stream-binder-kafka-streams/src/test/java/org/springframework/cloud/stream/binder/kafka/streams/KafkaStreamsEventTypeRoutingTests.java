/*
 * Copyright 2019-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.stream.binder.kafka.streams;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.streams.kstream.KStream;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.rule.EmbeddedKafkaRule;
import org.springframework.kafka.test.utils.KafkaTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

public class KafkaStreamsEventTypeRoutingTests {

	@ClassRule
	public static EmbeddedKafkaRule embeddedKafkaRule = new EmbeddedKafkaRule(1, true,
			"foo-1", "foo-2");

	private static EmbeddedKafkaBroker embeddedKafka = embeddedKafkaRule.getEmbeddedKafka();

	private static Consumer<Integer, Foo> consumer;

	@BeforeClass
	public static void setUp() {
		Map<String, Object> consumerProps = KafkaTestUtils.consumerProps("test-group-1", "false",
				embeddedKafka);
		consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
		consumerProps.put("value.deserializer", JsonDeserializer.class);
		consumerProps.put(JsonDeserializer.TRUSTED_PACKAGES, "*");
		DefaultKafkaConsumerFactory<Integer, Foo> cf = new DefaultKafkaConsumerFactory<>(consumerProps);
		consumer = cf.createConsumer();
		embeddedKafka.consumeFromEmbeddedTopics(consumer, "foo-2");
	}

	@AfterClass
	public static void tearDown() {
		consumer.close();
	}

	//See https://github.com/spring-cloud/spring-cloud-stream-binder-kafka/issues/1003 for more context on this test.
	@Test
	public void testRoutingWorksBasedOnEventTypes() {
		SpringApplication app = new SpringApplication(EventTypeRoutingTestConfig.class);
		app.setWebApplicationType(WebApplicationType.NONE);

		try (ConfigurableApplicationContext context = app.run(
				"--server.port=0",
				"--spring.jmx.enabled=false",
				"--spring.cloud.stream.function.definition=process",
				"--spring.cloud.stream.bindings.process-in-0.destination=foo-1",
				"--spring.cloud.stream.bindings.process-out-0.destination=foo-2",
				"--spring.cloud.stream.kafka.streams.bindings.process-in-0.consumer.eventTypes=foo,bar",
				"--spring.cloud.stream.kafka.streams.binder.functions.process.applicationId=process-id-foo-0",
				"--spring.cloud.stream.kafka.streams.binder.configuration.commit.interval.ms=1000",
				"--spring.cloud.stream.kafka.streams.binder.brokers=" + embeddedKafka.getBrokersAsString())) {
			Map<String, Object> senderProps = KafkaTestUtils.producerProps(embeddedKafka);
			senderProps.put("value.serializer", JsonSerializer.class);
			DefaultKafkaProducerFactory<Integer, Foo> pf = new DefaultKafkaProducerFactory<>(senderProps);
			try {
				KafkaTemplate<Integer, Foo> template = new KafkaTemplate<>(pf, true);
				template.setDefaultTopic("foo-1");
				Foo foo1 = new Foo();
				foo1.setFoo("foo-1");
				Headers headers = new RecordHeaders();
				headers.add(new RecordHeader("event_type", "foo".getBytes()));

				final ProducerRecord<Integer, Foo> producerRecord1 = new ProducerRecord<>("foo-1", 0, 56, foo1, headers);
				template.send(producerRecord1);

				Foo foo2 = new Foo();
				foo2.setFoo("foo-2");

				final ProducerRecord<Integer, Foo> producerRecord2 = new ProducerRecord<>("foo-1", 0, 57, foo2);
				template.send(producerRecord2);

				Foo foo3 = new Foo();
				foo3.setFoo("foo-3");

				final ProducerRecord<Integer, Foo> producerRecord3 = new ProducerRecord<>("foo-1", 0, 58, foo3, headers);
				template.send(producerRecord3);

				Foo foo4 = new Foo();
				foo4.setFoo("foo-4");
				Headers headers1 = new RecordHeaders();
				headers1.add(new RecordHeader("event_type", "bar".getBytes()));

				final ProducerRecord<Integer, Foo> producerRecord4 = new ProducerRecord<>("foo-1", 0, 59, foo4, headers1);
				template.send(producerRecord4);

				final ConsumerRecords<Integer, Foo> records = KafkaTestUtils.getRecords(consumer);

				assertThat(records.count()).isEqualTo(3);

				List<Integer> keys = new ArrayList<>();
				List<Foo> values = new ArrayList<>();

				records.forEach(integerFooConsumerRecord -> {
					keys.add(integerFooConsumerRecord.key());
					values.add(integerFooConsumerRecord.value());
				});

				assertThat(keys).containsExactlyInAnyOrder(56, 58, 59);
				assertThat(values).containsExactlyInAnyOrder(foo1, foo3, foo4);
			}
			finally {
				pf.destroy();
			}
		}
	}

	@EnableAutoConfiguration
	public static class EventTypeRoutingTestConfig {

		@Bean
		public Function<KStream<Integer, Foo>, KStream<Integer, Foo>> process() {
			return input -> input;
		}

	}

	static class Foo {
		String foo;

		public String getFoo() {
			return foo;
		}

		public void setFoo(String foo) {
			this.foo = foo;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (o == null || getClass() != o.getClass()) {
				return false;
			}
			Foo foo1 = (Foo) o;
			return Objects.equals(foo, foo1.foo);
		}

		@Override
		public int hashCode() {
			return Objects.hash(foo);
		}
	}
}
