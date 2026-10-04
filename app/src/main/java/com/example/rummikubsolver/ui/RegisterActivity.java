package com.example.rummikubsolver.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.rummikubsolver.R;
import com.example.rummikubsolver.net.AppApiClient;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

/**
 * Registration - Reached only from the "New user? Sign up" link on the login screen;
 * always opens with empty fields since it's a fresh Activity instance.
 * Can return from this screen to the login screen.
 * after pressing the register button there is a call for AppApiClient in order to make the HTTP request,
 * after it returns succesfuly the user is transferred to HomeActivity screen.
 */
public class RegisterActivity extends AppCompatActivity {
    // The networking client this screen uses to call the register endpoint.
    private final AppApiClient api = new AppApiClient();

    private TextInputEditText username, password;
    private MaterialButton btnRegister;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_register);

        username = findViewById(R.id.inputUsername);
        password = findViewById(R.id.inputPassword);
        btnRegister = findViewById(R.id.btnRegister);
        MaterialButton goToLogin = findViewById(R.id.btnGoToLogin);
        // Tapping register validates and submits the form.
        btnRegister.setOnClickListener(v -> submit());
        // finish(), not a new Intent to LoginActivity - LoginActivity is
        // still sitting on the back stack right underneath this screen, so
        // finishing just reveals it again instead of creating a second copy
        goToLogin.setOnClickListener(v -> finish());
    }
    // Validates the form and sends a registration request to the server.
    private void submit() {
        String u = username.getText() == null ? "" : username.getText().toString().trim();
        String p = password.getText() == null ? "" : password.getText().toString();
        // Reject if either field is empty.
        if (TextUtils.isEmpty(u) || TextUtils.isEmpty(p)) {
            Toast.makeText(this, R.string.login_error_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        // same rules as the login screen - reusing LoginActivity's patterns
        // rather than redefining them here
        if (!LoginActivity.USERNAME_PATTERN.matcher(u).matches()) {
            Toast.makeText(this, R.string.login_error_username_format, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!LoginActivity.PASSWORD_PATTERN.matcher(p).matches()) {
            Toast.makeText(this, R.string.login_error_password_format, Toast.LENGTH_SHORT).show();
            return;
        }
        // Disable the button to block double-submit while the request is in flight.
        btnRegister.setEnabled(false);
        // Send the actual registration request to the server.
        api.register(u, p, new AppApiClient.AuthCallback() {
            @Override
            public void onSuccess(AppApiClient.AuthResult result) {
                // Store the username, auth token, and user id into the shared session.
                TurnSession.get().setSession(result.username, result.token, result.userId);
                // Navigate to the home screen.
                startActivity(new Intent(RegisterActivity.this, HomeActivity.class));
                // Close this registration screen so back doesn't return to it.
                finish();
            }

            @Override
            public void onFailure(String message) {
                btnRegister.setEnabled(true);
                Toast.makeText(RegisterActivity.this, message, Toast.LENGTH_SHORT).show();
            }
        });
    }
}