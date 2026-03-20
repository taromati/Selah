package me.taromati.almah.setup;

import java.io.Console;
import java.util.List;
import java.util.Scanner;

/**
 * 터미널 I/O 유틸리티. ANSI 색상 + 프롬프트 + 선택지.
 */
public class ConsoleUi {

    private static final String RESET = "\033[0m";
    private static final String BOLD = "\033[1m";
    private static final String GREEN = "\033[32m";
    private static final String YELLOW = "\033[33m";
    private static final String CYAN = "\033[36m";
    private static final String RED = "\033[31m";

    private final Scanner scanner;

    public ConsoleUi() {
        this.scanner = new Scanner(System.in);
    }

    public void banner() {
        System.out.println();
        System.out.println(CYAN + BOLD + "  ╔═══════════════════════════════╗" + RESET);
        System.out.println(CYAN + BOLD + "  ║       Selah Setup Wizard      ║" + RESET);
        System.out.println(CYAN + BOLD + "  ╚═══════════════════════════════╝" + RESET);
        System.out.println();
    }

    public void section(String title) {
        System.out.println();
        System.out.println(BOLD + "── " + title + " ──" + RESET);
    }

    public void info(String message) {
        System.out.println("  " + message);
    }

    public void success(String message) {
        System.out.println("  " + GREEN + "✓ " + message + RESET);
    }

    public void warn(String message) {
        System.out.println("  " + YELLOW + "⚠ " + message + RESET);
    }

    public void error(String message) {
        System.out.println("  " + RED + "✗ " + message + RESET);
    }

    public String prompt(String label) {
        System.out.print("  " + CYAN + label + RESET + ": ");
        System.out.flush();
        return scanner.nextLine().trim();
    }

    public String prompt(String label, String defaultValue) {
        System.out.print("  " + CYAN + label + RESET + " [" + defaultValue + "]: ");
        System.out.flush();
        String input = scanner.nextLine().trim();
        return input.isEmpty() ? defaultValue : input;
    }

    public String promptSecret(String label) {
        Console console = System.console();
        if (console != null) {
            System.out.print("  " + CYAN + label + RESET + ": ");
            System.out.flush();
            char[] chars = console.readPassword();
            return chars != null ? new String(chars).trim() : "";
        }
        // IDE 등 console 없는 환경
        return prompt(label);
    }

    /**
     * 번호로 선택하는 메뉴. 1-based 인덱스 반환. 0 = 이전 단계.
     */
    public int choose(String label, List<String> options) {
        System.out.println("  " + CYAN + label + RESET);
        System.out.println("    " + YELLOW + "0) 이전 단계" + RESET);
        for (int i = 0; i < options.size(); i++) {
            System.out.println("    " + (i + 1) + ") " + options.get(i));
        }
        while (true) {
            System.out.print("  선택: ");
            System.out.flush();
            String input = scanner.nextLine().trim();
            try {
                int choice = Integer.parseInt(input);
                if (choice >= 0 && choice <= options.size()) {
                    return choice;
                }
            } catch (NumberFormatException ignored) {}
            System.out.println("  " + RED + "0~" + options.size() + " 사이의 숫자를 입력해주세요." + RESET);
        }
    }

    public boolean confirm(String label) {
        System.out.print("  " + CYAN + label + RESET + " [Y/n]: ");
        System.out.flush();
        String input = scanner.nextLine().trim().toLowerCase();
        return input.isEmpty() || input.equals("y") || input.equals("yes");
    }
}
