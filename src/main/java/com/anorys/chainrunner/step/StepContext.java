package com.anorys.chainrunner.step;

import com.anorys.chainrunner.config.ChainRunnerConfig.Environment;
import com.anorys.chainrunner.config.ChainRunnerConfig.Options;
import com.anorys.chainrunner.core.CallResult;
import com.anorys.chainrunner.core.Database;
import com.anorys.chainrunner.core.TokenClients;

import java.net.http.HttpClient;

/**
 * What the runner lends to a step while it renders or runs. Only {@link #env()} and {@link #options()}
 * are there when a request is only rendered (the screen's preview, {@code --dry-run}).
 */
public interface StepContext {

    Environment env();

    Options options();

    HttpClient http();

    TokenClients tokens();

    /**
     * The connection to the database named {@code name} (null: the environment's only one), opened at its
     * first use and closed at the end of the run.
     *
     * @throws StepException            when it cannot be reached; the message says which and why
     * @throws IllegalArgumentException when the environment has no such database
     */
    Database database(String name) throws StepException;

    /** Sleeps {@code nanos}, waking up to check for a stop; false when the user stopped the run. */
    boolean sleep(long nanos) throws InterruptedException;

    /** Whether the user stopped the run. */
    boolean cancelled();

    /** Shows a step still running: a poll after each attempt. */
    void progress(CallResult running);

    /**
     * A response body as shown and logged: JSON indented (the base64 text at the JSON pointer
     * {@code hidden} replaced by its size), the rest as it came, cut at {@code maxBodyChars}.
     */
    String display(String body, String hidden);

    /** A step cannot go on; the message is the step's error. */
    final class StepException extends Exception {

        public StepException(String message) {
            super(message);
        }
    }
}
