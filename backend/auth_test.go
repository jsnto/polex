package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/gin-gonic/gin"
)

func TestLoadAuthConfigTTLDefaults(t *testing.T) {
	t.Setenv("POLEX_TOKEN_TTL", "")
	t.Setenv("POLEX_REMEMBER_TTL", "")
	cfg := loadAuthConfig()
	if cfg.tokenTTL != 720*time.Hour {
		t.Errorf("default tokenTTL = %v, want 720h", cfg.tokenTTL)
	}
	if cfg.rememberTTL != 720*time.Hour {
		t.Errorf("default rememberTTL = %v, want 720h", cfg.rememberTTL)
	}
}

func TestLoadAuthConfigTTLFromEnv(t *testing.T) {
	t.Setenv("POLEX_TOKEN_TTL", "12h")
	t.Setenv("POLEX_REMEMBER_TTL", "168h")
	cfg := loadAuthConfig()
	if cfg.tokenTTL != 12*time.Hour {
		t.Errorf("tokenTTL = %v, want 12h", cfg.tokenTTL)
	}
	if cfg.rememberTTL != 7 * 24 * time.Hour {
		t.Errorf("rememberTTL = %v, want 168h", cfg.rememberTTL)
	}
}

func TestLoadAuthConfigInvalidTTLFallsBackToDefault(t *testing.T) {
	t.Setenv("POLEX_TOKEN_TTL", "not-a-duration")
	t.Setenv("POLEX_REMEMBER_TTL", "also-bad")
	cfg := loadAuthConfig()
	if cfg.tokenTTL != 720*time.Hour || cfg.rememberTTL != 720*time.Hour {
		t.Errorf("invalid TTLs: tokenTTL=%v rememberTTL=%v, want 720h/720h defaults", cfg.tokenTTL, cfg.rememberTTL)
	}
}

func TestTTLFor(t *testing.T) {
	a := authConfig{tokenTTL: time.Hour, rememberTTL: 720 * time.Hour}
	if got := a.ttlFor(true); got != 720*time.Hour {
		t.Errorf("ttlFor(true) = %v, want 720h", got)
	}
	if got := a.ttlFor(false); got != time.Hour {
		t.Errorf("ttlFor(false) = %v, want 1h", got)
	}
}

// TestSessionsPerTokenExpiry: tokens carry their own expiry, so a short-lived
// token dies while a longer-lived one issued alongside it stays valid.
func TestSessionsPerTokenExpiry(t *testing.T) {
	ss := newSessions()
	short := ss.create(20 * time.Millisecond)
	long := ss.create(time.Hour)
	time.Sleep(50 * time.Millisecond)
	if ss.valid(short) {
		t.Errorf("short-lived token still valid after expiry")
	}
	if !ss.valid(long) {
		t.Errorf("long-lived token invalid while within its TTL")
	}
	if ss.valid("") || ss.valid("no-such-token") {
		t.Errorf("empty/unknown token must be invalid")
	}
}

// loginRouter wires the login handler plus one protected route so tests can
// check which tokens still authenticate after a TTL elapses.
func loginRouter(t *testing.T, tokenTTL, rememberTTL time.Duration) (*gin.Engine, *state) {
	t.Helper()
	gin.SetMode(gin.TestMode)
	st := &state{
		auth: authConfig{
			username:    "u",
			password:    "p",
			tokenTTL:    tokenTTL,
			rememberTTL: rememberTTL,
		},
		sessions: newSessions(),
	}
	r := gin.New()
	r.POST("/api/login", st.login)
	r.GET("/api/version", st.authMiddleware(), st.version)
	return r, st
}

func login(r *gin.Engine, body string) (int, string) {
	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPost, "/api/login", strings.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	r.ServeHTTP(w, req)
	return w.Code, w.Body.String()
}

func getWithToken(r *gin.Engine, token string) int {
	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/api/version", nil)
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	r.ServeHTTP(w, req)
	return w.Code
}

func tokenFromBody(t *testing.T, body string) string {
	t.Helper()
	var out struct {
		OK    bool   `json:"ok"`
		Token string `json:"token"`
	}
	if err := json.Unmarshal([]byte(body), &out); err != nil {
		t.Fatalf("unmarshal %q: %v", body, err)
	}
	if !out.OK || out.Token == "" {
		t.Fatalf("expected ok token in %q", body)
	}
	return out.Token
}

// TestLoginRememberMe: remember=true grants the longer remember TTL while
// remember=false (and the legacy body without the field) keeps the regular
// TTL; the two expire independently.
func TestLoginRememberMe(t *testing.T) {
	r, st := loginRouter(t, 30*time.Millisecond, time.Hour)

	// Remember-me login.
	code, body := login(r, `{"username":"u","password":"p","remember":true}`)
	if code != http.StatusOK {
		t.Fatalf("remember login status = %d, body %s", code, body)
	}
	rememberToken := tokenFromBody(t, body)

	// Regular logins: explicit remember=false and the legacy body shape.
	code, body = login(r, `{"username":"u","password":"p","remember":false}`)
	if code != http.StatusOK {
		t.Fatalf("regular login status = %d, body %s", code, body)
	}
	regularToken := tokenFromBody(t, body)
	code, body = login(r, `{"username":"u","password":"p"}`)
	if code != http.StatusOK {
		t.Fatalf("legacy login status = %d, body %s", code, body)
	}
	legacyToken := tokenFromBody(t, body)

	// Immediately, all three authenticate.
	for name, tok := range map[string]string{"remember": rememberToken, "regular": regularToken, "legacy": legacyToken} {
		if got := getWithToken(r, tok); got != http.StatusOK {
			t.Errorf("%s token: /api/version = %d, want 200", name, got)
		}
	}

	// After the regular TTL elapses, only the remember-me token survives.
	time.Sleep(60 * time.Millisecond)
	if got := getWithToken(r, rememberToken); got != http.StatusOK {
		t.Errorf("remember token after regular TTL: /api/version = %d, want 200", got)
	}
	if got := getWithToken(r, regularToken); got != http.StatusUnauthorized {
		t.Errorf("regular token after TTL: /api/version = %d, want 401", got)
	}
	if got := getWithToken(r, legacyToken); got != http.StatusUnauthorized {
		t.Errorf("legacy token after TTL: /api/version = %d, want 401", got)
	}

	// The remember token's own expiry is the remember TTL, not the regular one.
	if got := st.sessions.valid(rememberToken); !got {
		t.Errorf("remember token invalid within its remember TTL")
	}
}

func TestLoginBadCredentials(t *testing.T) {
	r, _ := loginRouter(t, time.Hour, time.Hour)
	code, body := login(r, `{"username":"u","password":"wrong"}`)
	if code != http.StatusUnauthorized {
		t.Fatalf("status = %d, want 401 (body %s)", code, body)
	}
	if !strings.Contains(body, `"ok":false`) {
		t.Errorf("body %q, want ok:false", body)
	}
}
