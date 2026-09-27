package cl.erz.sailer.auth

sealed class LoginResult {
    /** The login form was submitted and the page navigated away from /login. */
    data object Success : LoginResult()

    /** The login page reloaded with the credentials rejected. */
    data object InvalidCredentials : LoginResult()

    /** The login page (or the follow-up navigation) could not be loaded at all. */
    data object NetworkError : LoginResult()

    /** The page loaded but didn't look like the login form we expect. */
    data class Unexpected(val message: String) : LoginResult()
}
