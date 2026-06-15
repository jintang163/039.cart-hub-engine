package com.carhub.service;

import com.carhub.config.CartHubProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.*;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class SensitiveWordFilterService {

    private final CartHubProperties cartHubProperties;

    private volatile Set<String> sensitiveWordSet = new HashSet<>();

    @PostConstruct
    public void init() {
        reloadSensitiveWords();
    }

    public void reloadSensitiveWords() {
        List<String> words = cartHubProperties.getRemark().getSensitiveWords();
        if (words != null && !words.isEmpty()) {
            Set<String> newSet = new HashSet<>();
            for (String word : words) {
                if (StringUtils.isNotBlank(word)) {
                    newSet.add(word.trim().toLowerCase());
                }
            }
            this.sensitiveWordSet = newSet;
            log.info("Sensitive words reloaded, count={}", newSet.size());
        }
    }

    public boolean containsSensitiveWord(String text) {
        if (StringUtils.isBlank(text)) {
            return false;
        }
        if (!Boolean.TRUE.equals(cartHubProperties.getRemark().getEnableSensitiveWordFilter())) {
            return false;
        }
        String lowerText = text.toLowerCase();
        for (String word : sensitiveWordSet) {
            if (lowerText.contains(word)) {
                return true;
            }
        }
        return false;
    }

    public List<String> findSensitiveWords(String text) {
        List<String> found = new ArrayList<>();
        if (StringUtils.isBlank(text)) {
            return found;
        }
        if (!Boolean.TRUE.equals(cartHubProperties.getRemark().getEnableSensitiveWordFilter())) {
            return found;
        }
        String lowerText = text.toLowerCase();
        for (String word : sensitiveWordSet) {
            if (lowerText.contains(word)) {
                found.add(word);
            }
        }
        return found;
    }

    public String filterSensitiveWords(String text) {
        if (StringUtils.isBlank(text)) {
            return text;
        }
        if (!Boolean.TRUE.equals(cartHubProperties.getRemark().getEnableSensitiveWordFilter())) {
            return text;
        }
        String result = text;
        for (String word : sensitiveWordSet) {
            if (result.toLowerCase().contains(word)) {
                String replacement = String.join("", Collections.nCopies(word.length(), "*"));
                result = result.replaceAll("(?i)" + Pattern.quote(word), replacement);
            }
        }
        return result;
    }

}
