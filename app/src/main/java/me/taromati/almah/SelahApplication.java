package me.taromati.almah;

import me.taromati.almah.setup.DoctorCheck;
import me.taromati.almah.setup.SelfUpdater;
import me.taromati.almah.setup.ServiceInstaller;
import me.taromati.almah.setup.ServiceRestarter;
import me.taromati.almah.setup.SetupWizard;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@EnableAsync
@EnableScheduling
@ConfigurationPropertiesScan
@SpringBootApplication(
        scanBasePackages = "me.taromati.almah",
        exclude = {DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class}
)
public class SelahApplication {

    public static final String VERSION = loadVersion();

    public static void main(String[] args) {
        // 인자 없으면 start와 동일 (서비스 등록 + 시작)
        if (args.length == 0) {
            runServiceInstall();
            return;
        }

        switch (args[0]) {
            case "_server" -> {
                // 서비스 매니저가 호출하는 내부 명령 → Spring Boot 서버 실행
                if (!Files.exists(Path.of("config.yml"))) {
                    System.out.println("[WARN] config.yml이 없습니다. 초기 설정: selah setup");
                }
                SpringApplication.run(SelahApplication.class, args);
            }
            case "setup" -> SetupWizard.run();
            case "start" -> runServiceInstall();
            case "stop" -> {
                try { ServiceInstaller.uninstall(); } catch (Exception e) {
                    System.err.println(e.getMessage());
                    System.exit(1);
                }
            }
            case "restart" -> {
                var result = ServiceRestarter.restart(false);
                if (result.success()) {
                    System.out.println(result.message());
                } else {
                    System.err.println(result.message());
                    System.exit(1);
                }
            }
            case "doctor" -> DoctorCheck.run();
            case "update" -> SelfUpdater.run();
            case "--version", "-v", "version" ->
                    System.out.println("Selah v" + VERSION);
            default ->
                    System.err.println("알 수 없는 명령: " + args[0]
                            + "\n사용 가능: start, stop, restart, setup, doctor, update, --version");
        }
    }

    private static String loadVersion() {
        // application.yml의 app.version (Gradle @project.version@ 치환)
        try (var is = SelahApplication.class.getResourceAsStream("/application.yml")) {
            if (is != null) {
                String content = new String(is.readAllBytes());
                for (String line : content.split("\n")) {
                    line = line.trim();
                    if (line.startsWith("version:")) {
                        return line.substring("version:".length()).trim();
                    }
                }
            }
        } catch (Exception ignored) {}
        return "unknown";
    }

    private static void runServiceInstall() {
        try {
            ServiceInstaller.install();
        } catch (Exception e) {
            System.err.println(e.getMessage());
            System.exit(1);
        }
    }
}
