package com.nexawallet.platform.modules.auth.service;

import com.nexawallet.platform.modules.auth.dto.AuthResponse;
import com.nexawallet.platform.modules.auth.dto.LoginRequest;
import com.nexawallet.platform.modules.auth.dto.RegisterRequest;
import com.nexawallet.platform.modules.auth.dto.UserResponse;

import java.util.UUID;

/**
 * Service contract defining user registration, authentication, and profile retrieval.
 */
public interface AuthService {

    /**
     * Registers a new customer, hashes their password, and creates their primary wallet and ledger account.
     */
    AuthResponse register(RegisterRequest request);

    /**
     * Authenticates credentials using AuthenticationManager and issues a signed JWT access token.
     */
    AuthResponse login(LoginRequest request);

    /**
     * Retrieves the profile summary of the currently authenticated user.
     */
    UserResponse getCurrentUser(UUID userId);
}
