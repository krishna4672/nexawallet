package com.nexawallet.platform.modules.auth.service;

import com.nexawallet.platform.common.cache.UserProfileCacheEntry;
import com.nexawallet.platform.common.cache.UserProfileCacheService;
import com.nexawallet.platform.common.exception.ResourceNotFoundException;
import com.nexawallet.platform.common.exception.UserAlreadyExistsException;
import com.nexawallet.platform.common.security.JwtTokenProvider;
import com.nexawallet.platform.modules.auth.dto.AuthResponse;
import com.nexawallet.platform.modules.auth.dto.LoginRequest;
import com.nexawallet.platform.modules.auth.dto.RegisterRequest;
import com.nexawallet.platform.modules.auth.dto.UserResponse;
import com.nexawallet.platform.modules.auth.entity.UserEntity;
import com.nexawallet.platform.modules.auth.entity.UserRole;
import com.nexawallet.platform.modules.auth.repository.UserRepository;
import com.nexawallet.platform.modules.ledger.entity.LedgerAccountEntity;
import com.nexawallet.platform.modules.ledger.entity.LedgerAccountType;
import com.nexawallet.platform.modules.ledger.repository.LedgerAccountRepository;
import com.nexawallet.platform.modules.wallet.entity.WalletEntity;
import com.nexawallet.platform.modules.wallet.entity.WalletStatus;
import com.nexawallet.platform.modules.wallet.repository.WalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.List;
import java.util.UUID;

/**
 * Implementation of AuthService managing user lifecycle, BCrypt password hashing,
 * wallet creation, and JWT issuance.
 */
@Service
public class AuthServiceImpl implements AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthServiceImpl.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final WalletRepository walletRepository;
    private final LedgerAccountRepository ledgerAccountRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtTokenProvider jwtTokenProvider;
    private final UserProfileCacheService userProfileCacheService;

    public AuthServiceImpl(
            UserRepository userRepository,
            WalletRepository walletRepository,
            LedgerAccountRepository ledgerAccountRepository,
            PasswordEncoder passwordEncoder,
            AuthenticationManager authenticationManager,
            JwtTokenProvider jwtTokenProvider,
            UserProfileCacheService userProfileCacheService
    ) {
        this.userRepository = userRepository;
        this.walletRepository = walletRepository;
        this.ledgerAccountRepository = ledgerAccountRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.jwtTokenProvider = jwtTokenProvider;
        this.userProfileCacheService = userProfileCacheService;
    }

    @Override
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String normalizedEmail = request.getEmail().toLowerCase().trim();

        // 1. Enforce unique email constraint
        if (userRepository.existsByEmail(normalizedEmail)) {
            log.warn("Registration rejected: email '{}' is already taken", normalizedEmail);
            throw new UserAlreadyExistsException("A user is already registered with email: " + normalizedEmail);
        }

        // 2. Hash password securely with BCrypt
        String passwordHash = passwordEncoder.encode(request.getPassword());

        // 3. Create and persist UserEntity
        UserEntity user = UserEntity.builder()
                .firstName(request.getResolvedFirstName())
                .lastName(request.getResolvedLastName())
                .email(normalizedEmail)
                .passwordHash(passwordHash)
                .role(UserRole.ROLE_CUSTOMER)
                .active(true)
                .build();
        UserEntity savedUser = userRepository.save(user);

        // 4. Automatically create primary USD wallet
        String walletNumber = generateUniqueWalletNumber();
        WalletEntity wallet = WalletEntity.builder()
                .user(savedUser)
                .walletNumber(walletNumber)
                .currency("USD")
                .balance(BigDecimal.ZERO.setScale(4))
                .status(WalletStatus.ACTIVE)
                .build();
        WalletEntity savedWallet = walletRepository.save(wallet);

        // 5. Automatically create corresponding ledger account for double-entry bookkeeping
        LedgerAccountEntity ledgerAccount = LedgerAccountEntity.builder()
                .accountType(LedgerAccountType.USER_WALLET)
                .wallet(savedWallet)
                .currency("USD")
                .build();
        ledgerAccountRepository.save(ledgerAccount);

        log.info("Registered new customer '{}' with wallet {}", normalizedEmail, walletNumber);

        // 6. Generate signed JWT access token
        String accessToken = jwtTokenProvider.generateToken(
                savedUser.getId(),
                savedUser.getEmail(),
                savedUser.getRole().name()
        );

        // Seed the identity-fields cache now, while we already have a freshly-loaded entity in
        // hand — a cheap write that means the very next GET /api/v1/auth/me is a cache hit, and
        // that any previously-cached (now impossible, since this id is brand new) entry can never
        // be stale.
        UserProfileCacheEntry profile = toProfile(savedUser);
        userProfileCacheService.put(savedUser.getId(), profile);

        UserResponse userResponse = buildUserResponse(profile, savedWallet);

        return AuthResponse.builder()
                .accessToken(accessToken)
                .tokenType("Bearer")
                .expiresIn(jwtTokenProvider.getExpirationMs() / 1000)
                .user(userResponse)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        String normalizedEmail = request.getEmail().toLowerCase().trim();

        // 1. Authenticate with Spring Security AuthenticationManager
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(normalizedEmail, request.getPassword())
        );

        // 2. Retrieve user entity from repository
        UserEntity user = userRepository.findByEmail(normalizedEmail)
                .orElseThrow(() -> new ResourceNotFoundException("User", normalizedEmail));

        if (!user.isActive()) {
            log.warn("Login rejected for disabled user: {}", normalizedEmail);
            throw new DisabledException("User account is disabled");
        }

        // 3. Retrieve user's primary wallet
        WalletEntity primaryWallet = findPrimaryWallet(user.getId());

        // 4. Generate signed JWT token
        String accessToken = jwtTokenProvider.generateToken(
                user.getId(),
                user.getEmail(),
                user.getRole().name()
        );

        log.info("Customer '{}' successfully logged in", normalizedEmail);

        // Refresh the cache on every successful login — this is the main invalidation mechanism
        // for the (currently immutable, no update-profile endpoint exists yet) identity fields:
        // a login always overwrites whatever was cached with a value just read from Postgres.
        UserProfileCacheEntry profile = toProfile(user);
        userProfileCacheService.put(user.getId(), profile);

        UserResponse userResponse = buildUserResponse(profile, primaryWallet);

        return AuthResponse.builder()
                .accessToken(accessToken)
                .tokenType("Bearer")
                .expiresIn(jwtTokenProvider.getExpirationMs() / 1000)
                .user(userResponse)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public UserResponse getCurrentUser(UUID userId) {
        // Read-through cache for the identity fields only. The wallet balance below is always a
        // fresh PostgreSQL read — a cache hit here can never serve a stale balance, because the
        // balance was never part of what got cached in the first place.
        UserProfileCacheEntry profile = userProfileCacheService.getOrLoad(userId, () -> toProfile(
                userRepository.findById(userId).orElseThrow(() -> new ResourceNotFoundException("User", userId))
        ));
        WalletEntity primaryWallet = findPrimaryWallet(userId);
        return buildUserResponse(profile, primaryWallet);
    }

    private WalletEntity findPrimaryWallet(UUID userId) {
        List<WalletEntity> wallets = walletRepository.findByUserId(userId);
        return wallets.isEmpty() ? null : wallets.get(0);
    }

    private UserResponse buildUserResponse(UserProfileCacheEntry profile, WalletEntity wallet) {
        return UserResponse.builder()
                .id(profile.getId())
                .email(profile.getEmail())
                .firstName(profile.getFirstName())
                .lastName(profile.getLastName())
                .role(profile.getRole())
                .walletNumber(wallet != null ? wallet.getWalletNumber() : null)
                .walletBalance(wallet != null ? wallet.getBalance() : BigDecimal.ZERO.setScale(4))
                .currency(wallet != null ? wallet.getCurrency() : "USD")
                .createdAt(profile.getCreatedAt())
                .build();
    }

    private UserProfileCacheEntry toProfile(UserEntity user) {
        return UserProfileCacheEntry.builder()
                .id(user.getId())
                .email(user.getEmail())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .role(user.getRole().name())
                .createdAt(user.getCreatedAt())
                .build();
    }

    private String generateUniqueWalletNumber() {
        String number;
        do {
            number = String.format("NX-%04d-%04d-%04d",
                    RANDOM.nextInt(10000),
                    RANDOM.nextInt(10000),
                    RANDOM.nextInt(10000));
        } while (walletRepository.existsByWalletNumber(number));
        return number;
    }
}
