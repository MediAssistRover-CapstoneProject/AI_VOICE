package com.mediassistrover.backend.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.mediassistrover.backend.entity.HospitalInformationArticle;
import com.mediassistrover.backend.enums.HospitalInformationStatus;
import com.mediassistrover.backend.exception.BusinessRuleException;
import com.mediassistrover.backend.repository.HospitalInformationArticleRepository;
import com.mediassistrover.backend.service.HospitalInformationQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class HospitalInformationQueryServiceImpl implements HospitalInformationQueryService {
    private static final String FALLBACK = "Tôi chưa có thông tin phù hợp. Vui lòng liên hệ nhân viên bệnh viện để được hỗ trợ.";
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern NON_WORD = Pattern.compile("[^a-z0-9]+");
    private static final Set<String> STOP_WORDS = Set.of("la","o","co","cho","toi","muon","hoi","ve","cua","benh","vien","xin","vui","long");
    private final HospitalInformationArticleRepository repository;
    private final Clock clock;

    @Override
    public InformationAnswer query(String question, String languageCode) {
        if (question == null || question.isBlank() || question.length() > 1000) {
            throw new BusinessRuleException("Hospital information question is required and must be at most 1000 characters");
        }
        String language = languageCode == null || languageCode.isBlank() ? "vi-VN" : languageCode.replace('_', '-');
        String normalizedQuestion = normalize(question);
        Set<String> tokens = tokens(normalizedQuestion);
        HospitalInformationArticle best = null;
        int bestScore = 0;
        for (HospitalInformationArticle candidate : repository.findEffectiveCandidates(
                HospitalInformationStatus.PUBLISHED, language, Instant.now(clock), PageRequest.of(0, 200))) {
            int score = score(normalizedQuestion, tokens, candidate);
            if (score > bestScore || score == bestScore && best != null
                    && candidate.getUpdatedAt().isAfter(best.getUpdatedAt())) {
                best = candidate;
                bestScore = score;
            }
        }
        if (best == null || bestScore < 8) {
            return new InformationAnswer(false, FALLBACK, "INFORMATION_UNAVAILABLE", null);
        }
        Source source = new Source(best.getArticleId(), best.getArticleCode(), best.getCategory(),
                best.getTitle(), best.getLanguageCode(), best.getUpdatedAt());
        return new InformationAnswer(true, best.getAnswer(), null, source);
    }

    private int score(String question, Set<String> tokens, HospitalInformationArticle article) {
        String canonical = normalize(article.getCanonicalQuestion());
        String title = normalize(article.getTitle());
        String category = normalize(article.getCategory());
        String answer = normalize(article.getAnswer());
        String keywords = keywords(article.getKeywordsJson());
        if (question.equals(canonical)) return 1000;
        int score = canonical.contains(question) || question.contains(canonical) ? 100 : 0;
        Set<String> canonicalTokens = tokens(canonical);
        Set<String> titleTokens = tokens(title);
        Set<String> keywordTokens = tokens(keywords);
        Set<String> categoryTokens = tokens(category);
        Set<String> answerTokens = tokens(answer);
        for (String token : tokens) {
            // Compare complete normalized tokens. Substring comparison made short
            // Vietnamese words such as "gi" match unrelated content accidentally.
            if (canonicalTokens.contains(token)) score += 12;
            if (titleTokens.contains(token)) score += 8;
            if (keywordTokens.contains(token)) score += 10;
            if (categoryTokens.contains(token)) score += 4;
            if (answerTokens.contains(token)) score += 1;
        }
        return score;
    }

    private String keywords(JsonNode node) {
        if (node == null || !node.isArray()) return "";
        StringBuilder value = new StringBuilder();
        node.forEach(item -> { if (item.isTextual()) value.append(' ').append(normalize(item.asText())); });
        return value.toString();
    }
    private Set<String> tokens(String value) {
        Set<String> result = new LinkedHashSet<>();
        for (String token : value.split(" ")) if (token.length() >= 2 && !STOP_WORDS.contains(token)) result.add(token);
        return result;
    }
    private String normalize(String value) {
        String decomposed = Normalizer.normalize(value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace('đ','d'), Normalizer.Form.NFD);
        return NON_WORD.matcher(MARKS.matcher(decomposed).replaceAll("")).replaceAll(" ").trim();
    }
}
