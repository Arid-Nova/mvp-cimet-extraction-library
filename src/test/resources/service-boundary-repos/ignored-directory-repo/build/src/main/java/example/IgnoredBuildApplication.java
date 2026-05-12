package example;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
@RestController
@RequestMapping("/ignored")
public class IgnoredBuildApplication {
    public static void main(String[] args) {
        SpringApplication.run(IgnoredBuildApplication.class, args);
    }
}
