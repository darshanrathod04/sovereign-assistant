package com.sovereign.core.react.model;

/**
 * <b>OperatorEventListener</b>
 *
 * <p>Listener interface for consuming streaming {@link OperatorEvent} instances.</p>
 */
@FunctionalInterface
public interface OperatorEventListener {
    void onEvent(OperatorEvent event);
}
