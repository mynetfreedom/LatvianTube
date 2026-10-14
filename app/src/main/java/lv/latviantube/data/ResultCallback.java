package lv.latviantube.data;

/**
 * Result callback for repository-level operations.
 *
 * <p>Distinct from the error types thrown lower down because the repository can
 * succeed with no network access at all (a fresh cache hit) and can fail while
 * holding a perfectly valid HTTP response (an empty filtered result).
 *
 * <p>Callbacks are always invoked on the main thread; see
 * {@link ContentRepository} for the threading contract.
 */
public interface ResultCallback<T> {

    void onResult(T result);

    /**
     * @param error      the failure
     * @param diagnostic a human-readable explanation, already localised where
     *                   appropriate, safe to show on screen
     */
    void onError(Exception error, String diagnostic);
}
