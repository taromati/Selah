package me.taromati.almah.agent.suggest;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "plugins.agent", name = "enabled", havingValue = "true")
public class ExplorationCollector implements StimulusCollector {

    private final RestTemplate restTemplate;

    public ExplorationCollector() {
        this.restTemplate = new RestTemplate();
    }

    public ExplorationCollector(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Override
    public StimulusCategory category() { return StimulusCategory.EXPLORATION; }

    @Override
    @SuppressWarnings("unchecked")
    public StimulusResult collect() {
        List<String> topics = new ArrayList<>();
        StringBuilder context = new StringBuilder();

        // HN Top Stories
        try {
            var hnIds = restTemplate.getForObject(
                    "https://hacker-news.firebaseio.com/v0/topstories.json", int[].class);
            if (hnIds != null) {
                context.append("Hacker News 인기 글:\n");
                int limit = Math.min(hnIds.length, 10);
                for (int i = 0; i < limit; i++) {
                    try {
                        var item = restTemplate.getForObject(
                                "https://hacker-news.firebaseio.com/v0/item/" + hnIds[i] + ".json",
                                Map.class);
                        if (item != null && item.get("title") != null) {
                            String title = (String) item.get("title");
                            topics.add(title);
                            context.append("- ").append(title).append("\n");
                        }
                    } catch (Exception e) { /* skip individual item */ }
                }
            }
        } catch (Exception e) {
            log.warn("[ExplorationCollector] HN 수집 실패: {}", e.getMessage());
        }

        return new StimulusResult(category(), topics,
                context.isEmpty() ? "" : context.toString());
    }
}
