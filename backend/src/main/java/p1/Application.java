package p1;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import p1.config.ExternalConfigBootstrap;

@EnableAsync
@EnableScheduling
@SpringBootApplication
public class Application {

	public static void main(String[] args) {
		ExternalConfigBootstrap.prepare();
		SpringApplication.run(Application.class, args);
	}

}
