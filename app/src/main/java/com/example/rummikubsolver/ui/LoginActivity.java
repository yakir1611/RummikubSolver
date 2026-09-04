//package com.example.rummikubsolver.ui;

//import android.content.Intent;
//import android.os.Bundle;
//import android.text.TextUtils;
//import android.widget.Toast;

//import androidx.appcompat.app.AppCompatActivity;

//import com.example.rummikubsolver.R;
//import com.example.rummikubsolver.net.AppApiClient;
//import com.google.android.material.button.MaterialButton;
//import com.google.android.material.textfield.TextInputEditText;

//import java.util.regex.Pattern;

/**
 * Login / register entry.
 *
 * Login and Register both hit the Node server now (AppApiClient) - a real
 * account with a password, not just a typed name.
 */
//public class LoginActivity extends AppCompatActivity {

    // mirrors the server's USERNAME_PATTERN/PASSWORD_PATTERN (authController.js)
    // - checked here first so an obviously-invalid attempt never even needs a
    // network call, but the server still enforces the same rules itself
  //  private static final Pattern USERNAME_PATTERN = Pattern.compile("^[A-Za-z0-9]+$");
    //private static final Pattern PASSWORD_PATTERN =
      //      Pattern.compile("^(?=.*[A-Za-z])(?=.*\\d)[A-Za-z0-9]{5,}$");

    //private final AppApiClient api = new AppApiClient();

    //private TextInputEditText username, password;
    //private MaterialButton btnLogin, btnRegister;

    //@Override
    //protected void onCreate(Bundle savedInstanceState) {
      //  super.onCreate(savedInstanceState);
        //setContentView(R.layout.activity_login);

        //username = findViewById(R.id.inputUsername);
        //password = findViewById(R.id.inputPassword);
        //btnLogin = findViewById(R.id.btnLogin);
        //btnRegister = findViewById(R.id.btnRegister);

        //btnLogin.setOnClickListener(v -> submit(/* isRegister = */ false));
        //btnRegister.setOnClickListener(v -> submit(/* isRegister = */ true));
    //}

    //private void submit(boolean isRegister) {
      //  String u = username.getText() == null ? "" : username.getText().toString().trim();
        //String p = password.getText() == null ? "" : password.getText().toString();
        //if (TextUtils.isEmpty(u) || TextUtils.isEmpty(p)) {
          //  Toast.makeText(this, R.string.login_error_empty, Toast.LENGTH_SHORT).show();
            //return;
        //}
        //if (!USERNAME_PATTERN.matcher(u).matches()) {
          //  Toast.makeText(this, R.string.login_error_username_format, Toast.LENGTH_SHORT).show();
            //return;
        //}
        //if (!PASSWORD_PATTERN.matcher(p).matches()) {
          //  Toast.makeText(this, R.string.login_error_password_format, Toast.LENGTH_SHORT).show();
            //return;
        //}

        //setButtonsEnabled(false); // block double-submit while the request is in flight
        //AppApiClient.AuthCallback callback = new AppApiClient.AuthCallback() {
          //  @Override
            //public void onSuccess(AppApiClient.AuthResult result) {
              //  TurnSession.get().setSession(result.username, result.token, result.userId);
                //enter();
            //}

            //@Override
            //public void onFailure(String message) {
              //  setButtonsEnabled(true);
                //Toast.makeText(LoginActivity.this, message, Toast.LENGTH_SHORT).show();
            //}
        //};

        //if (isRegister) {
          //  api.register(u, p, callback);
        //} else {
          //  api.login(u, p, callback);
        //}
    //}

    //private void setButtonsEnabled(boolean enabled) {
      //  btnLogin.setEnabled(enabled);
        //btnRegister.setEnabled(enabled);
    //}

    //private void enter() {
      //  startActivity(new Intent(this, HomeActivity.class));
        //finish(); // no going back to login with the back button
    //}
//}
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
 * Login entry point. Registration now lives on its own screen
 * (RegisterActivity) - this Activity only signs an EXISTING user in.
 */
public class LoginActivity extends AppCompatActivity {

    // package-private (no "private") on purpose - RegisterActivity, which
    // lives in the same "ui" package, reuses these two patterns instead of
    // redefining them, so the two screens can never silently drift apart
    static final Pattern USERNAME_PATTERN = Pattern.compile("^[A-Za-z0-9]+$");
    static final Pattern PASSWORD_PATTERN =
            Pattern.compile("^(?=.*[A-Za-z])(?=.*\\d)[A-Za-z0-9]{5,}$");

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

        btnLogin.setOnClickListener(v -> submit());
        // pure navigation - no validation here. RegisterActivity starts with
        // its own empty form and does its own validation from scratch
        goToRegister.setOnClickListener(v ->
                startActivity(new Intent(this, RegisterActivity.class)));
    }

    private void submit() {
        String u = username.getText() == null ? "" : username.getText().toString().trim();
        String p = password.getText() == null ? "" : password.getText().toString();
        if (TextUtils.isEmpty(u) || TextUtils.isEmpty(p)) {
            Toast.makeText(this, R.string.login_error_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!USERNAME_PATTERN.matcher(u).matches()) {
            Toast.makeText(this, R.string.login_error_username_format, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!PASSWORD_PATTERN.matcher(p).matches()) {
            Toast.makeText(this, R.string.login_error_password_format, Toast.LENGTH_SHORT).show();
            return;
        }

        btnLogin.setEnabled(false); // block double-submit while the request is in flight
        api.login(u, p, new AppApiClient.AuthCallback() {
            @Override
            public void onSuccess(AppApiClient.AuthResult result) {
                TurnSession.get().setSession(result.username, result.token, result.userId);
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