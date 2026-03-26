package me.taromati.almah.agent.tool;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import me.taromati.almah.agent.config.AgentConfigProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Playwright 브라우저 인스턴스의 생명주기를 관리한다.
 * <p>
 * - Lazy init: 첫 호출 시에만 Chromium 시작
 * - Auto-close: N분 비활동 후 자동 종료
 * - Single instance: 브라우저 1개, 페이지 1개
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "plugins.agent", name = "enabled", havingValue = "true")
public class BrowserManager {

    private final AgentConfigProperties.BrowserConfig config;
    private final ReentrantLock lock = new ReentrantLock();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "browser-auto-close");
        t.setDaemon(true);
        return t;
    });

    private Playwright playwright;
    private Browser browser;
    private Page page;
    private ScheduledFuture<?> autoCloseFuture;

    public BrowserManager(AgentConfigProperties config) {
        this.config = config.getBrowser();
    }

    public boolean isActive() {
        lock.lock();
        try {
            return page != null && !page.isClosed();
        } finally {
            lock.unlock();
        }
    }

    public Page getOrCreatePage() {
        lock.lock();
        try {
            if (page != null && !page.isClosed()) {
                resetAutoCloseTimer();
                return page;
            }

            log.info("[BrowserManager] Starting Chromium (headless={})...", config.getHeadless());
            long start = System.currentTimeMillis();

            if (playwright == null) {
                playwright = Playwright.create();
            }

            browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                    .setHeadless(config.getHeadless()));
            page = browser.newPage();

            long elapsed = System.currentTimeMillis() - start;
            log.info("[BrowserManager] Chromium started in {}ms", elapsed);

            resetAutoCloseTimer();
            return page;
        } finally {
            lock.unlock();
        }
    }

    public void closeBrowser() {
        lock.lock();
        try {
            doClose("explicit close");
        } finally {
            lock.unlock();
        }
    }

    public void touch() {
        lock.lock();
        try {
            resetAutoCloseTimer();
        } finally {
            lock.unlock();
        }
    }

    @PreDestroy
    void destroy() {
        lock.lock();
        try {
            scheduler.shutdownNow();
            doClose("app shutdown");
        } finally {
            lock.unlock();
        }
    }

    private void doClose(String reason) {
        cancelAutoCloseTimer();

        if (page != null) {
            try { page.close(); } catch (Exception e) { /* ignore */ }
            page = null;
        }
        if (browser != null) {
            try { browser.close(); } catch (Exception e) { /* ignore */ }
            browser = null;
        }
        if (playwright != null) {
            try { playwright.close(); } catch (Exception e) { /* ignore */ }
            playwright = null;
        }

        log.info("[BrowserManager] Browser closed ({})", reason);
    }

    private void resetAutoCloseTimer() {
        cancelAutoCloseTimer();
        int minutes = config.getAutoCloseMinutes();
        autoCloseFuture = scheduler.schedule(() -> {
            lock.lock();
            try {
                if (page != null && !page.isClosed()) {
                    doClose("auto-close after " + minutes + " min inactivity");
                }
            } finally {
                lock.unlock();
            }
        }, minutes, TimeUnit.MINUTES);
    }

    private void cancelAutoCloseTimer() {
        if (autoCloseFuture != null && !autoCloseFuture.isDone()) {
            autoCloseFuture.cancel(false);
        }
    }
}
