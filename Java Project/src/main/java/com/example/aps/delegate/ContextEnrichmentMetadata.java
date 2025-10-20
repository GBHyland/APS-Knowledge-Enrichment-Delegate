package com.example.aps.delegate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.activiti.engine.delegate.DelegateExecution;
import org.activiti.engine.delegate.JavaDelegate;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.*;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Base64;

public class ContextEnrichmentMetadata implements JavaDelegate {

    // ======== STATIC APS CONFIG ========
    private static final String APS_API_BASE    = "http://gb-alf-25.alfdemo.com/activiti-app/api";
    private static final String APS_AUTH_HEADER = "Basic ZGVtbzpkZW1v"; // demo:demo base64 – replace for your env

    // ======== CONTEXT ENRICHMENT CONFIG ========
    private static final String CONTEXT_API_BASE = "https://knowledge-enrichment.ai.experience.hyland.com/latest/api/context-enrichment";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public void execute(DelegateExecution execution) throws Exception {
        String accessToken = (String) execution.getVariable("accessToken");
        if (accessToken == null || accessToken.isEmpty()) {
            throw new RuntimeException("Missing access token; run OAuthApiCallDelegate first.");
        }

        // === STEP 1: Get presigned URL ===
        System.out.println("( 1 ) ==========>> Get presigned URL for upload");
        String presignUrl;
        String resourceName;
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            String url = CONTEXT_API_BASE + "/files/upload/presigned-url?contentType=application%2Fpdf";
            HttpGet presignRequest = new HttpGet(url);
            presignRequest.setHeader("Authorization", "Bearer " + accessToken);

            try (CloseableHttpResponse response = client.execute(presignRequest)) {
                String respText = EntityUtils.toString(response.getEntity());
                if (response.getStatusLine().getStatusCode() != 200) {
                    throw new RuntimeException("Failed to get presigned URL: "
                            + response.getStatusLine() + " - " + respText);
                }
                JsonNode json = MAPPER.readTree(respText);
                presignUrl   = json.get("presignedUrl").asText();
                resourceName = json.get("objectKey").asText();
            }
        }

        // === STEP 2: Resolve objPDF and upload ===
        System.out.println("( 2 ) ==========>> Resolve objPDF and upload to presigned URL");
        Object pdfObj = execution.getVariable("objPDF");
        byte[] fileBytes = resolvePdfBytes(pdfObj);
        if (fileBytes == null || fileBytes.length == 0) {
            throw new RuntimeException("Could not resolve 'objPDF' to PDF bytes.");
        }

        // Log PDF size and first bytes
        System.out.println("PDF size: " + fileBytes.length + " bytes");
        System.out.print("First 8 bytes (hex): ");
        for (int i = 0; i < Math.min(fileBytes.length, 8); i++) {
            System.out.printf("%02X ", fileBytes[i]);
        }
        System.out.println();

        // Quick magic check (starts with "%PDF-")
        if (!(fileBytes.length >= 4
                && fileBytes[0] == 0x25 /*%*/
                && fileBytes[1] == 0x50 /*P*/
                && fileBytes[2] == 0x44 /*D*/
                && fileBytes[3] == 0x46 /*F*/)) {
            System.out.println("⚠ Warning: Uploaded bytes do not look like a PDF (no %PDF header). Proceeding anyway.");
        }

        RequestConfig noExpect = RequestConfig.custom().setExpectContinueEnabled(false).build();
        try (CloseableHttpClient client = HttpClients.custom().setDefaultRequestConfig(noExpect).build()) {
            HttpPut uploadRequest = new HttpPut(presignUrl);

            // Binary body – this IS a binary upload
            ByteArrayEntity entity = new ByteArrayEntity(fileBytes, ContentType.create("application/pdf"));
            uploadRequest.setEntity(entity);

            // Headers that many presigned URLs expect
            uploadRequest.setHeader("Content-Type", "application/pdf");
            //uploadRequest.setHeader("Content-Length", String.valueOf(fileBytes.length));

            try (CloseableHttpResponse uploadResp = client.execute(uploadRequest)) {
                int status = uploadResp.getStatusLine().getStatusCode();
                String respBody = uploadResp.getEntity() != null ? EntityUtils.toString(uploadResp.getEntity()) : "";
                if (status < 200 || status >= 300) {
                    throw new RuntimeException("File upload failed: " + status + " - " + respBody);
                }
            }
        }

        // === STEP 3: Ask for text-metadata-generation ===
        System.out.println("( 3 ) ==========>> Call Context Enrichment API (text-metadata-generation)");
        String processingId;
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpPost processRequest = new HttpPost(CONTEXT_API_BASE + "/content/process");
            processRequest.setHeader("Authorization", "Bearer " + accessToken);
            processRequest.setHeader("Content-Type", "application/json");
            processRequest.setHeader("accept", "application/json");

            ObjectNode bodyNode = MAPPER.createObjectNode();
            bodyNode.putArray("objectKeys").add(resourceName);
            bodyNode.putArray("actions").add("text-metadata-generation");
            bodyNode.put("contentType", "application/pdf");  // ensure it matches your upload

            // Strong extraction prompt to reduce nulls
            bodyNode.put("prompt",
                    "Extract a JSON object named car_metadata from the PDF. " +
                            "Return exactly these keys: manufacturer, model, color, year, car_part, damage_type, damage_severity, confidence_score. " +
                            "If a field is not present, use null. " +
                            "Use only information present in the PDF, do not guess."
            );
            // Optional OCR hints (safe to include; ignored if unsupported)
            bodyNode.put("useOcr", true);
            bodyNode.put("ocrMode", "auto");
            bodyNode.put("language", "en"); // if your docs are English

            // Few-shot style seed metadata (optional, helps with shape/labels)
            ArrayNode kSimilarMetadata = bodyNode.putArray("kSimilarMetadata");
            ObjectNode wrapper = MAPPER.createObjectNode();
            ObjectNode carMeta = MAPPER.createObjectNode();
            carMeta.put("manufacturer", "Pontiac");
            carMeta.put("model", "Firebird");
            carMeta.put("color", "red");
            carMeta.put("year", "1992");
            carMeta.put("car_part", "bumper");
            carMeta.put("damage_type", "minimal");
            carMeta.put("damage_severity", "low");
            carMeta.put("confidence_score", "10");
            wrapper.set("car_metadata", carMeta);
            kSimilarMetadata.add(wrapper);

            System.out.println("( 3.1 ) BodyNode ==========>> " + bodyNode.toString());

            processRequest.setEntity(new StringEntity(bodyNode.toString(), StandardCharsets.UTF_8));

            try (CloseableHttpResponse resp = client.execute(processRequest)) {
                String respText = EntityUtils.toString(resp.getEntity());
                if (resp.getStatusLine().getStatusCode() != 200) {
                    throw new RuntimeException("Failed to process content: "
                            + resp.getStatusLine() + " - " + respText);
                }
                JsonNode resultJson = MAPPER.readTree(respText);
                processingId = resultJson.get("processingId").asText();
                System.out.println("Processing ID: " + processingId);
            }
        }

        // === STEP 4: Poll results ===
        System.out.println("( 4 ) ==========>> Polling for results");
        JsonNode textMetadataNode = null;
        int maxAttempts = 12;
        int attempt = 0;
        boolean resultsReady = false;

        try (CloseableHttpClient client = HttpClients.createDefault()) {
            String resultsUrl = CONTEXT_API_BASE + "/content/process/" + processingId + "/results";

            while (attempt < maxAttempts && !resultsReady) {
                HttpGet resultsRequest = new HttpGet(resultsUrl);
                resultsRequest.setHeader("Authorization", "Bearer " + accessToken);
                resultsRequest.setHeader("accept", "application/json");

                try (CloseableHttpResponse resp = client.execute(resultsRequest)) {
                    String respText = EntityUtils.toString(resp.getEntity());
                    int sc = resp.getStatusLine().getStatusCode();
                    if (sc != 200 && sc != 202) {
                        throw new RuntimeException("Failed to retrieve results: "
                                + resp.getStatusLine() + " - " + respText);
                    }

                    JsonNode resultsJson = MAPPER.readTree(respText);
                    String status = resultsJson.path("status").asText("");
                    JsonNode resultsArray = resultsJson.path("results");

                    if ("PROCESSING".equalsIgnoreCase(status)) {
                        System.out.println("Results not ready yet... attempt " + (attempt + 1));
                    } else if (resultsArray.isArray() && resultsArray.size() > 0) {
                        JsonNode firstResult = resultsArray.get(0);
                        if (firstResult.has("textMetadata")) {
                            textMetadataNode = firstResult.get("textMetadata");
                            resultsReady = true;
                            System.out.println("Received textMetadata: " + textMetadataNode);
                        }
                    }
                }

                if (!resultsReady) {
                    attempt++;
                    Thread.sleep(10_000);
                }
            }
        }

        if (!resultsReady) {
            System.out.println("Warning: Results were not ready after max polling attempts.");
            textMetadataNode = MAPPER.createObjectNode();
        }

        // === STEP 5: Store variables ===
        System.out.println("( 5 ) ==========>> Store metadata variables in process context");
        execution.setVariable("uploadedResourceName", resourceName);

        if (textMetadataNode != null && textMetadataNode.has("result")) {
            System.out.println("( 5.1 ) ========>> RESULTS: " + textMetadataNode);
            // you mapped to car_metadata in your last version
            JsonNode claimNode = textMetadataNode.path("result").path("car_metadata");
            if (claimNode != null && !claimNode.isMissingNode()) {
                execution.setVariable("veh_make",        claimNode.path("manufacturer").asText(""));
                execution.setVariable("veh_model",       claimNode.path("model").asText(""));
                execution.setVariable("veh_color",       claimNode.path("color").asText(""));
                execution.setVariable("veh_year",        claimNode.path("year").asText(""));
                execution.setVariable("veh_part",        claimNode.path("car_part").asText(""));
                execution.setVariable("damage_type",     claimNode.path("damage_type").asText(""));
                execution.setVariable("damage_severity", claimNode.path("damage_severity").asText(""));
            } else {
                System.out.println("( 5 ERROR ) ==========>> Bad path to metadata in JSON.");
            }
        }
    }

    // ---------- Helpers ----------

    private static byte[] resolvePdfBytes(Object pdfObj) throws Exception {
        if (pdfObj == null) return null;

        if (pdfObj instanceof byte[]) return (byte[]) pdfObj;

        if (pdfObj instanceof Number) {
            long contentId = ((Number) pdfObj).longValue();
            return fetchApsContentBytesById(contentId);
        }

        if (pdfObj instanceof String) {
            String s = (String) pdfObj;
            if (s.startsWith("data:application/pdf;base64,")) {
                String b64 = s.substring("data:application/pdf;base64,".length());
                return Base64.getDecoder().decode(b64);
            }
            if (looksLikeBase64(s)) {
                try { return Base64.getDecoder().decode(s); } catch (IllegalArgumentException ignore) {}
            }
            if (s.endsWith(".pdf")) {
                return Files.readAllBytes(Paths.get(s));
            }
        }

        throw new RuntimeException("Unsupported objPDF type: " + pdfObj.getClass());
    }

    private static byte[] fetchApsContentBytesById(long contentId) throws Exception {
        final String base = APS_API_BASE.replaceAll("/+$", "");
        final String[] urlCandidates = new String[] {
                base + "/enterprise/content/" + contentId + "/raw",
                base + "/app/rest/content/" + contentId + "/raw"
        };

        try (CloseableHttpClient client = HttpClients.createDefault()) {
            for (String url : urlCandidates) {
                // Attempt 1: Accept */*
                HttpGet get1 = new HttpGet(url);
                get1.setHeader("Authorization", APS_AUTH_HEADER);
                get1.setHeader("Accept", "*/*");
                try (CloseableHttpResponse resp = client.execute(get1)) {
                    int sc = resp.getStatusLine().getStatusCode();
                    if (sc == 200) return EntityUtils.toByteArray(resp.getEntity());
                    String body = resp.getEntity() != null ? EntityUtils.toString(resp.getEntity()) : "";
                    if (sc != 406) {
                        throw new RuntimeException("APS content fetch failed: " + sc + " - " + body);
                    }
                }

                // Attempt 2: no Accept header
                HttpGet get2 = new HttpGet(url);
                get2.setHeader("Authorization", APS_AUTH_HEADER);
                try (CloseableHttpResponse resp = client.execute(get2)) {
                    int sc = resp.getStatusLine().getStatusCode();
                    if (sc == 200) return EntityUtils.toByteArray(resp.getEntity());
                    String body = resp.getEntity() != null ? EntityUtils.toString(resp.getEntity()) : "";
                    if (sc == 406) continue; // try next candidate
                    throw new RuntimeException("APS content fetch failed: " + sc + " - " + body);
                }
            }
        }

        throw new RuntimeException("APS content fetch failed with 406 on all known paths.");
    }

    private static boolean looksLikeBase64(String s) {
        if (s == null || s.length() < 16) return false;
        if ((s.length() % 4) != 0) return false;
        return s.matches("^[A-Za-z0-9+/=\\r\\n]+$");
    }
}
