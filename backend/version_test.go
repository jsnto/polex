package main

import (
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/gin-gonic/gin"
)

func TestVersionEndpoint(t *testing.T) {
	gin.SetMode(gin.TestMode)
	r := gin.New()
	st := &state{}
	r.GET("/api/version", st.version)

	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/api/version", nil)
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("status = %d, want %d (body %s)", w.Code, http.StatusOK, w.Body.String())
	}
	want := `{"version":"` + Version + `"}`
	if got := w.Body.String(); got != want {
		t.Fatalf("body = %s, want %s", got, want)
	}
}
