package dev.turboj.demo;

import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
@RestController
public class HelloService {
    public static void main(String[] args) {
        SpringApplication.run(HelloService.class, args);
    }

    @GetMapping("/hello")
    public Map<String, Object> hello() {
        return Map.of("status", 200, "message", "OK");
    }
}
