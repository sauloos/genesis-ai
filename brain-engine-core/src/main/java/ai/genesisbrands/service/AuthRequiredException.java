package ai.genesisbrands.service;

/** Thrown when a visitor without a valid client session attempts to reach or advance
 *  into a Page marked requiresAuth. Mapped to 403 by the controller layer. */
public class AuthRequiredException extends RuntimeException {

    public AuthRequiredException(String message) {
        super(message);
    }
}
