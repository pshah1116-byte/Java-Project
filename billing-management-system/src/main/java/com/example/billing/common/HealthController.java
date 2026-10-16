package com.example.billing.common;

import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GET /api/health - reports whether MongoDB is reachable.
 * The frontend calls this to show a warning banner when the database is down.
 */
@RestController
@RequestMapping("/api/health")
public class HealthController {

    private final MongoTemplate mongoTemplate;

    public HealthController(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("application", "UP");
        body.put("databaseName", mongoTemplate.getDb().getName());
        try {
            mongoTemplate.executeCommand(new Document("ping", 1));
            body.put("database", "UP");
            return ResponseEntity.ok(body);
        } catch (Exception ex) {
            body.put("database", "DOWN");
            body.put("message", "Cannot connect to MongoDB. Is the MongoDB server running?");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
        }
    }
}
