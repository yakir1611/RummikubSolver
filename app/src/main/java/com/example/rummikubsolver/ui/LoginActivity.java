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

import java.util.regex.Pattern;

/**
 * This file is the login screen of the app,
 * in this file we can move to the register screen if we do not have a user, or we can log in if we have one,
 * after filling the name and password, we turn to AppApiClient in order to make the http log in request.
 */
public class LoginActivity extends AppCompatActivity {
    // Matches the server's username rule: letters and digits only.
    static final Pattern USERNAME_PATTERN = Pattern.compile("^[A-Za-z0-9]+$");
    // Matches the server's password rule: at least one letter, one digit, length 5+.
    static final Pattern PASSWORD_PATTERN =
            Pattern.compile("^(?=.*[A-Za-z])(?=.*\\d)[A-Za-z0-9]{5,}$");
    // The networking client this screen uses to call the login endpoint.
    private final AppApiClient api = new AppApiClient();

    private TextInputEditText username, password;
    private MaterialButton btnLogin;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        username = findViewById(R.id.inputUsername);
        password = findViewById(R.id.inputPassword);
        btnLogin = findViewById(R.id.btnLogin);
        MaterialButton goToRegister = findViewById(R.id.btnGoToRegister);
        // Tapping login validates and submits the form.
        btnLogin.setOnClickListener(v -> submit());
        // pure navigation - no validation here. RegisterActivity starts with
        // its own empty form and does its own validation from scratch
        goToRegister.setOnClickListener(v ->
                startActivity(new Intent(this, RegisterActivity.class)));
    }
    // Validates the form and sends a login request to the server.
    private void submit() {
        String u = username.getText() == null ? "" : username.getText().toString().trim();
        String p = password.getText() == null ? "" : password.getText().toString();
        // Reject if either field is empty.
        if (TextUtils.isEmpty(u) || TextUtils.isEmpty(p)) {
            Toast.makeText(this, R.string.login_error_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        // Reject if the username doesn't match the allowed character pattern.
        if (!USERNAME_PATTERN.matcher(u).matches()) {
            Toast.makeText(this, R.string.login_error_username_format, Toast.LENGTH_SHORT).show();
            return;
        }
        // Reject if the password doesn't meet the required strength pattern.
        if (!PASSWORD_PATTERN.matcher(p).matches()) {
            Toast.makeText(this, R.string.login_error_password_format, Toast.LENGTH_SHORT).show();
            return;
        }

        btnLogin.setEnabled(false); // block double-submit while the request is in flight
        // Send the actual login request to the server.
        api.login(u, p, new AppApiClient.AuthCallback() {
            @Override
            public void onSuccess(AppApiClient.AuthResult result) {
                // Store the username, auth token, and user id into the shared session.
                TurnSession.get().setSession(result.username, result.token, result.userId);
                // Navigate to the home screen.
                startActivity(new Intent(LoginActivity.this, HomeActivity.class));
                finish(); // no going back to login with the back button
            }

            @Override
            public void onFailure(String message) {
                btnLogin.setEnabled(true);
                Toast.makeText(LoginActivity.this, message, Toast.LENGTH_SHORT).show();
            }
        });
    }
}