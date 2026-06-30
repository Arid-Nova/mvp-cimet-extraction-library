package edu.university.ecs.lab.common.utils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class GitHubTokenClient {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public GitHubTokenClient() {
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();
    }

    public String fetchToken() {
        try {
            String configServerUrl = System.getenv().getOrDefault("CONFIG_SERVER_URL",
                    "http://cloudhub_repohandler:8020/settings/github-token");
            String internalKey = System.getenv().getOrDefault("INTERNAL_SERVICE_KEY", "");

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(configServerUrl))
                    .header("X-Internal-Service-Auth", internalKey)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                return null;
            }

            JsonNode rootNode = objectMapper.readTree(response.body());
            if (rootNode.has("token") && !rootNode.get("token").isNull()) {
                return rootNode.get("token").asText();
            }
            return null;

        } catch (Exception e) {
            // System.err.println("Failed to fetch token: " + e.getMessage());
            return null;
        }
    }
}