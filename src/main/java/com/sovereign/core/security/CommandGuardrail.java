package com.sovereign.core.security;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * <b>CommandGuardrail</b>
 *
 * <p>Deterministic security interceptor that prevents execution of catastrophic,
 * destructive, or privilege-escalating commands prior to OS process dispatch.</p>
 */
public class CommandGuardrail {

    private static final List<DestructiveRule> RULES = new ArrayList<>();

    private record DestructiveRule(Pattern pattern, String category, String description) {}

    static {
        // 1. Recursive root / system deletion
        addRule(
                "(?i)\\brm\\b(?=.*(-[a-zA-Z]*r[a-zA-Z]*f*|-r\\b|--recursive))(?=.*(\\s+(\\/|\\/\\*|~|~\\/|\\.\\/|\\$HOME|\\$ROOT|[a-zA-Z]:\\\\?))).*",
                "ROOT_DELETION",
                "Recursive root or home filesystem deletion (e.g., rm -rf /)"
        );
        addRule(
                "(?i)\\b(rd|rmdir)\\b(?=.*(/[sq]|/q|/s))(?=.*([a-zA-Z]:\\\\|\\/|\\*)).*",
                "ROOT_DELETION",
                "Recursive directory removal on Windows system roots (e.g., rmdir /s /q C:\\)"
        );
        addRule(
                "(?i)\\bdel\\b(?=.*(/[sfq]|/q|/s|/f))(?=.*([a-zA-Z]:\\\\|\\*)).*",
                "ROOT_DELETION",
                "Forced recursive deletion on Windows drive roots (e.g., del /f /s /q C:\\*)"
        );
        addRule(
                "(?i)\\bRemove-Item\\b(?=.*(-Recurse|-r\\b))(?=.*([a-zA-Z]:\\\\?|\\/|\\*)).*",
                "ROOT_DELETION",
                "PowerShell recursive root deletion (e.g., Remove-Item C:\\ -Recurse -Force)"
        );

        // 2. Drive wiping and formatting
        addRule(
                "(?i)\\bformat\\s+.*[a-zA-Z]:.*",
                "DRIVE_WIPING",
                "Drive formatting command (e.g., format C:)"
        );
        addRule(
                "(?i)\\bmkfs(\\.[a-zA-Z0-9]+)?\\s+.*(/dev/).*",
                "DRIVE_WIPING",
                "Filesystem creation / disk wiping on block devices (e.g., mkfs.ext4 /dev/sda)"
        );
        addRule(
                "(?i)\\bdiskpart(\\s+.*)?",
                "DRIVE_WIPING",
                "Disk partitioning utility execution"
        );
        addRule(
                "(?i)\\bdd\\s+.*(of=/dev/(sd[a-z]|nvme[0-9]|hd[a-z]|null|zero|mem)).*",
                "DRIVE_WIPING",
                "Raw device block overwrite via dd"
        );
        addRule(
                "(?i)\\bshred\\s+.*(/dev/|/|[a-zA-Z]:\\\\).*",
                "DRIVE_WIPING",
                "Raw device or root filesystem shredding"
        );
        addRule(
                "(?i)\\b(Clear-Disk|Initialize-Disk|Format-Volume)\\b.*",
                "DRIVE_WIPING",
                "PowerShell disk initialization or volume wipe"
        );

        // 3. Raw fork bombs & infinite process spawning
        addRule(
                ":\\s*\\(\\s*\\)\\s*\\{\\s*:\\s*\\|\\s*:\\s*&\\s*\\}\\s*;\\s*:",
                "FORK_BOMB",
                "Bash raw fork bomb"
        );
        addRule(
                "(?i)%\\s*0\\s*\\|\\s*%\\s*0",
                "FORK_BOMB",
                "Windows batch fork bomb (%0|%0)"
        );
        addRule(
                "(?i)\\$0\\s*\\|\\s*\\$0",
                "FORK_BOMB",
                "Shell fork bomb ($0|$0)"
        );
        addRule(
                "(?i)while\\s*\\(\\s*\\$true\\s*\\)\\s*\\{\\s*Start-Process.*\\}",
                "FORK_BOMB",
                "PowerShell process spawning loop"
        );

        // 4. Unauthorized system reboot / shutdown
        addRule(
                "(?i)\\b(shutdown|reboot|poweroff|halt)\\b.*",
                "SYSTEM_REBOOT",
                "System shutdown or reboot command (e.g., shutdown /s, reboot)"
        );
        addRule(
                "(?i)\\binit\\s+[06]\\b.*",
                "SYSTEM_REBOOT",
                "SysV init state change to reboot or halt"
        );
        addRule(
                "(?i)\\b(Stop-Computer|Restart-Computer)\\b.*",
                "SYSTEM_REBOOT",
                "PowerShell system shutdown or restart"
        );
        addRule(
                "(?i)\\bsystemctl\\s+(reboot|poweroff|halt|suspend).*",
                "SYSTEM_REBOOT",
                "Systemd reboot or poweroff"
        );

        // 5. Destructive permission / ownership resets
        addRule(
                "(?i)\\bchmod\\s+(-R|--recursive)\\s+(777|000)\\s+(\\/|\\/\\*|~|~\\/).*",
                "PERMISSION_OVERWRITE",
                "Catastrophic recursive chmod on root or home directory"
        );
        addRule(
                "(?i)\\btakeown\\s+/f\\s+[a-zA-Z]:\\\\.*",
                "PERMISSION_OVERWRITE",
                "Takeown command on entire Windows drive root"
        );
    }

    private static void addRule(String regex, String category, String description) {
        RULES.add(new DestructiveRule(Pattern.compile(regex), category, description));
    }

    /**
     * Validates a command against guardrail rules. Throws {@link SecurityException}
     * if the command matches any destructive pattern.
     */
    public static void validate(String command) {
        if (command == null || command.isBlank()) {
            return;
        }

        String trimmed = command.trim();

        // Check command as a whole and broken down by subcommands (separated by ;, &&, ||, |)
        String[] subCommands = trimmed.split("[;&|]+");
        for (String sub : subCommands) {
            String cleanSub = sub.trim();
            if (cleanSub.isEmpty()) {
                continue;
            }
            for (DestructiveRule rule : RULES) {
                if (rule.pattern.matcher(cleanSub).matches() || rule.pattern.matcher(cleanSub).find()) {
                    throw new SecurityException(String.format(
                            "Command blocked by CommandGuardrail [%s]: %s (Command: '%s')",
                            rule.category, rule.description, cleanSub
                    ));
                }
            }
        }

        // Also check raw trimmed command in case separator splitting altered structure (e.g., fork bombs)
        for (DestructiveRule rule : RULES) {
            if (rule.pattern.matcher(trimmed).matches() || rule.pattern.matcher(trimmed).find()) {
                throw new SecurityException(String.format(
                        "Command blocked by CommandGuardrail [%s]: %s (Command: '%s')",
                        rule.category, rule.description, trimmed
                ));
            }
        }
    }

    /**
     * Checks if a command is permitted by the guardrail without throwing an exception.
     */
    public static boolean isAllowed(String command) {
        try {
            validate(command);
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }

    /**
     * Non-static validation instance method.
     */
    public void validateCommand(String command) {
        validate(command);
    }

    /**
     * Non-static permission check instance method.
     */
    public boolean isCommandAllowed(String command) {
        return isAllowed(command);
    }
}
