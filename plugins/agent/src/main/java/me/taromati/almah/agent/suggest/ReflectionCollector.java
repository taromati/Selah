package me.taromati.almah.agent.suggest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.agent.task.TaskStoreService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "plugins.agent", name = "enabled", havingValue = "true")
public class ReflectionCollector implements StimulusCollector {

    private final TaskStoreService taskStoreService;

    @Override
    public StimulusCategory category() { return StimulusCategory.REFLECTION; }

    @Override
    public StimulusResult collect() {
        StringBuilder context = new StringBuilder();
        List<String> topics = new ArrayList<>();

        var completed = taskStoreService.findByStatus("COMPLETED");
        if (!completed.isEmpty()) {
            context.append("최근 완료된 작업:\n");
            int limit = Math.min(completed.size(), 5);
            for (int i = 0; i < limit; i++) {
                var task = completed.get(i);
                context.append("- ").append(task.getTitle()).append("\n");
                topics.add(task.getTitle());
            }
        }

        var active = taskStoreService.findActive();
        if (!active.isEmpty()) {
            context.append("현재 진행 중인 작업:\n");
            for (var task : active) {
                context.append("- [").append(task.getStatus()).append("] ")
                        .append(task.getTitle()).append("\n");
            }
        }

        var failed = taskStoreService.findByStatus("FAILED");
        if (!failed.isEmpty()) {
            context.append("실패한 작업:\n");
            for (var task : failed) {
                context.append("- ").append(task.getTitle())
                        .append(" (").append(task.getProgress()).append(")\n");
                topics.add("재시도: " + task.getTitle());
            }
        }

        return new StimulusResult(category(), topics,
                context.isEmpty() ? "" : context.toString());
    }
}
