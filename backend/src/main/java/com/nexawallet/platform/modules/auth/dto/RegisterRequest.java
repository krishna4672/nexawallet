package com.nexawallet.platform.modules.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Registration request payload.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RegisterRequest {

    @Schema(description = "User's first name", example = "Alex")
    private String firstName;

    @Schema(description = "User's last name", example = "Hunter")
    private String lastName;

    @Schema(description = "Full name (alternative convenience field)", example = "Alex Hunter")
    private String name;

    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email address format")
    @Schema(description = "User's unique email address", example = "alex.hunter@example.com")
    private String email;

    @NotBlank(message = "Password is required")
    @Size(min = 8, max = 100, message = "Password must be between 8 and 100 characters")
    @Schema(description = "Account password (min 8 characters)", example = "P@ssw0rd123!")
    private String password;

    /**
     * Resolves effective first name, supporting both explicit firstName and combined name input.
     */
    public String getResolvedFirstName() {
        if (firstName != null && !firstName.isBlank()) {
            return firstName.trim();
        }
        if (name != null && !name.isBlank()) {
            String[] parts = name.trim().split("\\s+", 2);
            return parts[0];
        }
        return "Customer";
    }

    /**
     * Resolves effective last name, supporting both explicit lastName and combined name input.
     */
    public String getResolvedLastName() {
        if (lastName != null && !lastName.isBlank()) {
            return lastName.trim();
        }
        if (name != null && !name.isBlank()) {
            String[] parts = name.trim().split("\\s+", 2);
            return parts.length > 1 ? parts[1] : "User";
        }
        return "User";
    }
}
