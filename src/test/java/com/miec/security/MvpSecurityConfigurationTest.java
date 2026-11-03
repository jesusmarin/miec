package com.miec.security;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Configuración sin claves por defecto, separación de identidades y hash de contraseñas. */
class MvpSecurityConfigurationTest {
    /** No configurar claves deja cero usuarios y no genera un acceso implícito. */
    @Test void shouldDisableUnconfiguredAccounts() {
        var configuration=new MvpSecurityConfiguration(); var encoder=configuration.mvpPasswordEncoder();
        var users=configuration.mvpUsers(encoder,"author","","approver","");
        assertFalse(users.userExists("author")); assertFalse(users.userExists("approver"));
    }
    /** Identidades ambiguas, claves compartidas o muy cortas se rechazan; los hashes no conservan texto plano. */
    @Test void shouldValidateAccountsAndStoreEncodedCredentials() {
        var configuration=new MvpSecurityConfiguration(); var encoder=configuration.mvpPasswordEncoder();
        String author="author-test-secret-2026",approver="reviewer-test-secret-2026";
        assertThrows(IllegalArgumentException.class,()->configuration.mvpUsers(encoder,"Author",author,"author",approver));
        assertThrows(IllegalArgumentException.class,()->configuration.mvpUsers(encoder,"author",author,"approver",author));
        assertThrows(IllegalArgumentException.class,()->configuration.mvpUsers(encoder,"author","short","approver",""));
        var users=configuration.mvpUsers(encoder,"author",author,"approver",approver);
        String encoded=users.loadUserByUsername("author").getPassword();
        assertNotEquals(author,encoded); assertTrue(encoder.matches(author,encoded));
    }
}
