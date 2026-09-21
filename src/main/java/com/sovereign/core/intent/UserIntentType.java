package com.sovereign.core.intent;

/**
 * <b>UserIntentType</b>
 *
 * <p>Categorization of user input into distinct semantic intent channels.</p>
 */
public enum UserIntentType {
    /** Greetings, pleasantries, and open-ended conversational questions ("Hello", "How are you?"). */
    CHAT,

    /** Personal identity, preferences, and memory queries ("I am Darshan", "Remember my editor is VS Code", "What's my name?"). */
    MEMORY,

    /** Concrete system, shell, file, or process operations ("Run git status", "mvn clean compile", "Create file foo.txt"). */
    EXECUTE,

    /** Workspace and repository intelligence queries ("Analyze this Maven project", "Find all Java source directories"). */
    PROJECT,

    /** Assistant runtime status, session uptime, configuration, help, or exit commands. */
    SYSTEM
}
