package main

import (
	"crypto/rand"
	"encoding/hex"
	"log"
	"net/http"
	"os"
	"strings"
	"sync"
	"time"

	"github.com/gin-gonic/gin"
)

// ---------- auth config ----------

type authConfig struct {
	username string
	password string
	tokenTTL time.Duration
}

// loadAuthConfig reads credentials and token TTL. Precedence: process
// environment (also populated from .env by loadDotEnv) over defaults.
func loadAuthConfig() authConfig {
	cfg := authConfig{
		username: os.Getenv("POLEX_USERNAME"),
		password: os.Getenv("POLEX_PASSWORD"),
		tokenTTL: 720 * time.Hour, // 30 days
	}
	if v := os.Getenv("POLEX_TOKEN_TTL"); v != "" {
		if d, err := time.ParseDuration(v); err == nil {
			cfg.tokenTTL = d
		} else {
			log.Printf("warn: invalid POLEX_TOKEN_TTL %q, using default", v)
		}
	}
	if cfg.username == "" || cfg.password == "" {
		cfg.username = "admin"
		cfg.password = "polex"
		log.Printf("WARN: POLEX_USERNAME/POLEX_PASSWORD not set — using default credentials (admin/polex). Set them in backend/.env!")
	}
	return cfg
}

// ---------- session store (in-memory) ----------

type sessions struct {
	mu     sync.Mutex
	tokens map[string]time.Time // token -> expiry
	ttl    time.Duration
}

func newSessions(ttl time.Duration) *sessions {
	return &sessions{tokens: map[string]time.Time{}, ttl: ttl}
}

func (ss *sessions) create() string {
	buf := make([]byte, 32)
	if _, err := rand.Read(buf); err != nil {
		// crypto/rand never fails on Linux; fall back to time-based
		log.Printf("warn: crypto/rand failed: %v", err)
		return time.Now().String()
	}
	token := hex.EncodeToString(buf)
	ss.mu.Lock()
	defer ss.mu.Unlock()
	ss.purge()
	ss.tokens[token] = time.Now().Add(ss.ttl)
	return token
}

func (ss *sessions) valid(token string) bool {
	if token == "" {
		return false
	}
	ss.mu.Lock()
	defer ss.mu.Unlock()
	exp, ok := ss.tokens[token]
	if !ok {
		return false
	}
	if time.Now().After(exp) {
		delete(ss.tokens, token)
		return false
	}
	return true
}

func (ss *sessions) purge() {
	now := time.Now()
	for t, exp := range ss.tokens {
		if now.After(exp) {
			delete(ss.tokens, t)
		}
	}
}

// ---------- handlers / middleware ----------

type loginRequest struct {
	Username string `json:"username"`
	Password string `json:"password"`
}

func (s *state) login(c *gin.Context) {
	var in loginRequest
	if err := c.ShouldBindJSON(&in); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"ok": false, "error": "invalid json"})
		return
	}
	if in.Username != s.auth.username || in.Password != s.auth.password {
		c.JSON(http.StatusUnauthorized, gin.H{"ok": false, "error": "invalid credentials"})
		return
	}
	token := s.sessions.create()
	c.JSON(http.StatusOK, gin.H{"ok": true, "token": token})
}

// authMiddleware requires "Authorization: Bearer <token>" with a token
// issued by /api/login.
func (s *state) authMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		header := c.GetHeader("Authorization")
		token := strings.TrimPrefix(header, "Bearer ")
		if !s.sessions.valid(token) {
			c.AbortWithStatusJSON(http.StatusUnauthorized, gin.H{"ok": false, "error": "unauthorized"})
			return
		}
		c.Next()
	}
}
