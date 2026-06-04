package com.genesisai.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ContextEnrichmentService {

    private static final Logger log = LoggerFactory.getLogger(ContextEnrichmentService.class);
    private static final int MAX_ATTACHMENT_WORDS = 8_000;
    private static final int MAX_URL_WORDS = 2_000;

    private static final Pattern URL_PATTERN = Pattern.compile(
        "https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+", Pattern.CASE_INSENSITIVE);

    public String extractFromFile(MultipartFile file) {
        if (file == null || file.isEmpty()) return null;
        String name = file.getOriginalFilename() != null ? file.getOriginalFilename().toLowerCase() : "";
        try {
            if (name.endsWith(".pdf")) {
                try (PDDocument doc = PDDocument.load(file.getInputStream())) {
                    String text = new PDFTextStripper().getText(doc);
                    return truncate(text, MAX_ATTACHMENT_WORDS);
                }
            } else {
                String text = new String(file.getBytes(), StandardCharsets.UTF_8);
                return truncate(text, MAX_ATTACHMENT_WORDS);
            }
        } catch (Exception e) {
            log.warn("Failed to extract text from attachment {}: {}", name, e.getMessage());
            return null;
        }
    }

    public List<UrlContent> fetchUrls(String message) {
        List<UrlContent> results = new ArrayList<>();
        Matcher m = URL_PATTERN.matcher(message);
        while (m.find()) {
            String url = m.group();
            try {
                String text = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0")
                    .timeout(8_000)
                    .get()
                    .body().text();
                results.add(new UrlContent(url, truncate(text, MAX_URL_WORDS)));
            } catch (Exception e) {
                log.warn("Failed to fetch URL {}: {}", url, e.getMessage());
            }
        }
        return results;
    }

    private String truncate(String text, int maxWords) {
        String[] words = text.split("\\s+");
        if (words.length <= maxWords) return text.trim();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < maxWords; i++) {
            if (i > 0) sb.append(' ');
            sb.append(words[i]);
        }
        sb.append("\n[...truncated]");
        return sb.toString();
    }

    public record UrlContent(String url, String text) {}
}
