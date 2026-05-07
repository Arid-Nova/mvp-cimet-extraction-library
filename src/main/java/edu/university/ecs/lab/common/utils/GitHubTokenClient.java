package edu.university.ecs.lab.common.utils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import java.time.Duration;
import java.time.temporal.TemporalAmount;

import com.macasaet.fernet.Key;
import com.macasaet.fernet.Token;
import com.macasaet.fernet.StringValidator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class GitHubTokenClient {

    private static final String CONFIG_SERVER_URL = "http://localhost:8020/settings/github-token";
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public GitHubTokenClient() {
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();
    }

    public String fetchAndDecryptToken() {
        try {
            // Retrieving any GitHub Token
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(CONFIG_SERVER_URL))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                // System.out.println("Token not found or server unavailable.");
                return null;
            }

            JsonNode rootNode = objectMapper.readTree(response.body());
            String encryptedToken = rootNode.get("token").asText();

            String envKey = System.getenv("ENCRYPTION_KEY");
            Key key = new Key(envKey);

            StringValidator validator = new StringValidator() {
                @Override
                public TemporalAmount getTimeToLive() {
                    return Duration.ofDays(36500);
                }
            };

            Token fernetToken = Token.fromString(encryptedToken);
            return fernetToken.validateAndDecrypt(key, validator);

        } catch (Exception e) {
            // System.err.println("Failed to fetch and decrypt token: " + e.getMessage());
            return null;
        }
    }
}