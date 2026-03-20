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

    public static final String VERSION = "0.1.0";

    public static void main(String[] args) {
        if (args.length > 0) {
            switch (args[0]) {
                case "setup" -> { SetupWizard.run(); return; }
                case "enable" -> {
                    try { ServiceInstaller.install(); } catch (Exception e) {
                        System.err.println(e.getMessage());
                        System.exit(1);
                    }
                    return;
                }
                case "disable" -> {
                    try { ServiceInstaller.uninstall(); } catch (Exception e) {
                        System.err.println(e.getMessage());
                        System.exit(1);
                    }
                    return;
                }
                case "restart" -> {
                    var result = ServiceRestarter.restart(false);
                    if (result.success()) {
                        System.out.println(result.message());
                    } else {
                        System.err.println(result.message());
                        System.exit(1);
                    }
                    return;
                }
                case "doctor" -> { DoctorCheck.run(); return; }
                case "update" -> { SelfUpdater.run(); return; }
                case "--version", "-v", "version" -> {
                    System.out.println("Selah v" + VERSION);
                    return;
                }
            }
        }

        if (!Files.exists(Path.of("config.yml"))) {
            System.out.println("[WARN] config.yml이 없습니다. 초기 설정: selah setup");
        }

        SpringApplication.run(SelahApplication.class, args);
    }
}
