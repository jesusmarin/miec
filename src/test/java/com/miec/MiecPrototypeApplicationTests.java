package com.miec;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:miec_context_smoke;DB_CLOSE_DELAY=-1")
class MiecPrototypeApplicationTests {

	@Test
	void contextLoads() {
	}

}
