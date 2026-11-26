package zw.haulmate;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class HaulmateApplication {
    public static void main(String[] args) {
        SpringApplication.run(HaulmateApplication.class, args);
    }
}
