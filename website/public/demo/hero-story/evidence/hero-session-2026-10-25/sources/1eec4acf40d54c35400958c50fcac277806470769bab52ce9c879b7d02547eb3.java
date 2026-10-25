package dev.turboj.demo;

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
    public String hello() {
        return "Hello from TurboJ!\n";
    }
}
