package main

import (
	"bytes"
	"crypto/ed25519"
	"crypto/rand"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/gin-gonic/gin"
	"golang.org/x/crypto/ssh"
)

// ---------- pure helpers ----------

func TestNormalizeSSHFields(t *testing.T) {
	// Both set: accepted and trimmed.
	sv := Server{Name: "NAS", IP: "192.168.1.10", SshUser: "  root  ", SshKeyPath: "  ~/keys/polex  "}
	if err := normalize(&sv); err != nil {
		t.Fatalf("normalize: %v", err)
	}
	if sv.SshUser != "root" || sv.SshKeyPath != "~/keys/polex" {
		t.Fatalf("ssh fields not trimmed: %q %q", sv.SshUser, sv.SshKeyPath)
	}

	// Neither set: fine (shutdown disabled).
	sv = Server{Name: "NAS", IP: "192.168.1.10"}
	if err := normalize(&sv); err != nil {
		t.Fatalf("normalize (no ssh): %v", err)
	}

	// Exactly one set: rejected.
	for _, sv := range []Server{
		{Name: "NAS", IP: "192.168.1.10", SshUser: "root"},
		{Name: "NAS", IP: "192.168.1.10", SshKeyPath: "/keys/polex"},
	} {
		if err := normalize(&sv); err == nil {
			t.Fatalf("normalize: half-set ssh pair accepted (user=%q key=%q)", sv.SshUser, sv.SshKeyPath)
		}
	}
}

func TestSshConfigured(t *testing.T) {
	cases := []struct {
		user, key string
		want      bool
	}{
		{"", "", false},
		{"root", "", false},
		{"", "/keys/polex", false},
		{"root", "/keys/polex", true},
	}
	for _, c := range cases {
		if got := (Server{SshUser: c.user, SshKeyPath: c.key}).sshConfigured(); got != c.want {
			t.Errorf("sshConfigured(%q, %q) = %v, want %v", c.user, c.key, got, c.want)
		}
	}
}

func TestShutdownTarget(t *testing.T) {
	oldPort := sshPort
	defer func() { sshPort = oldPort }()

	sshPort = 22
	if got := shutdownTarget("192.168.1.10"); got != "192.168.1.10:22" {
		t.Errorf("shutdownTarget = %q", got)
	}
	sshPort = 2222
	if got := shutdownTarget("192.168.1.10"); got != "192.168.1.10:2222" {
		t.Errorf("shutdownTarget = %q", got)
	}
}

func TestExpandHome(t *testing.T) {
	home, err := os.UserHomeDir()
	if err != nil {
		t.Skipf("no home dir: %v", err)
	}
	cases := map[string]string{
		"~/keys/polex":     home + "/keys/polex",
		"~":                home,
		"/keys/polex":      "/keys/polex",
		"relative/key":     "relative/key",
		"~user/keys/polex": "~user/keys/polex",
	}
	for in, want := range cases {
		if got := expandHome(in); got != want {
			t.Errorf("expandHome(%q) = %q, want %q", in, got, want)
		}
	}
}

// ---------- handler tests (in-process SSH server) ----------

// genTestKey writes an ed25519 private key (OpenSSH PEM) into dir and
// returns the path.
func genTestKey(t *testing.T, dir string) string {
	t.Helper()
	_, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatalf("generate key: %v", err)
	}
	pemBlock, err := ssh.MarshalPrivateKey(priv, "")
	if err != nil {
		t.Fatalf("marshal key: %v", err)
	}
	var buf bytes.Buffer
	if err := pem.Encode(&buf, pemBlock); err != nil {
		t.Fatalf("encode key: %v", err)
	}
	path := dir + "/test_key"
	if err := os.WriteFile(path, buf.Bytes(), 0600); err != nil {
		t.Fatalf("write key: %v", err)
	}
	return path
}

// fakeSSHServer starts an in-process SSH server on 127.0.0.1 that accepts
// any key and answers the `systemctl poweroff` exec request per mode:
//
//	"ok"   — exit status 0 (clean acceptance)
//	"fail" — stderr + exit status 127
//	"drop" — accept the exec request, then drop the TCP connection
//	         (simulates the host powering off mid-session)
//	"authfail" — reject the key during authentication
func fakeSSHServer(t *testing.T, mode string) int {
	t.Helper()
	_, hostPriv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatalf("host key: %v", err)
	}
	hostSigner, err := ssh.NewSignerFromSigner(hostPriv)
	if err != nil {
		t.Fatalf("host signer: %v", err)
	}

	cfg := &ssh.ServerConfig{}
	cfg.AddHostKey(hostSigner)
	cfg.PublicKeyCallback = func(ssh.ConnMetadata, ssh.PublicKey) (*ssh.Permissions, error) {
		if mode == "authfail" {
			return nil, fmt.Errorf("key rejected")
		}
		return nil, nil // accept any key
	}

	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	t.Cleanup(func() { ln.Close() })

	go func() {
		for {
			nc, err := ln.Accept()
			if err != nil {
				return
			}
			go func(nc net.Conn) {
				defer nc.Close()
				sc, chans, reqs, err := ssh.NewServerConn(nc, cfg)
				if err != nil {
					return
				}
				defer sc.Close()
				go ssh.DiscardRequests(reqs)
				for newCh := range chans {
					if newCh.ChannelType() != "session" {
						_ = newCh.Reject(ssh.UnknownChannelType, "only session")
						continue
					}
					ch, chReqs, err := newCh.Accept()
					if err != nil {
						return
					}
					go ssh.DiscardRequests(chReqs)
					for req := range chReqs {
						if req.WantReply {
							_ = req.Reply(req.Type == "exec", nil)
						}
						if req.Type != "exec" {
							continue
						}
						if mode == "fail" {
							_, _ = ch.Stderr().Write([]byte("sh: systemctl: not found\n"))
							_, _ = ch.SendRequest("exit-status", false, ssh.Marshal(struct {
								Status uint32
							}{127}))
						} else if mode != "drop" {
							_, _ = ch.SendRequest("exit-status", false, ssh.Marshal(struct {
								Status uint32
							}{0}))
						}
						_ = ch.Close()
						if mode == "drop" {
							// Let the exec reply drain, then tear the
							// connection down like a powering-off host.
							time.Sleep(100 * time.Millisecond)
							nc.Close()
						}
						return
					}
				}
			}(nc)
		}
	}()
	return ln.Addr().(*net.TCPAddr).Port
}

// newTestBackend builds a state with the given servers plus the shutdown
// and status routes. The process CWD is moved to a temp dir for the test
// so the handler's save() (data.json) can't touch the source tree.
func newTestBackend(t *testing.T, servers ...Server) (*gin.Engine, *state) {
	t.Helper()
	wd, err := os.Getwd()
	if err != nil {
		t.Fatal(err)
	}
	tmp := t.TempDir()
	if err := os.Chdir(tmp); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { os.Chdir(wd) })

	st := &state{
		servers:      servers,
		status:       map[int]StatusView{},
		lastWol:      map[int]string{},
		lastShutdown: map[int]string{},
		nextID:       len(servers) + 1,
	}
	r := gin.New()
	r.POST("/api/servers/:id/shutdown", st.shutdown)
	r.GET("/api/status", st.listStatus)
	return r, st
}

func doShutdown(t *testing.T, r *gin.Engine) *httptest.ResponseRecorder {
	t.Helper()
	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPost, "/api/servers/1/shutdown", nil)
	r.ServeHTTP(w, req)
	return w
}

func doStatus(t *testing.T, r *gin.Engine) []StatusView {
	t.Helper()
	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/api/status", nil)
	r.ServeHTTP(w, req)
	var items []StatusView
	if err := json.Unmarshal(w.Body.Bytes(), &items); err != nil {
		t.Fatalf("status: %v (body %s)", err, w.Body.String())
	}
	return items
}

type shutdownResp struct {
	OK             bool   `json:"ok"`
	Message        string `json:"message"`
	LastShutdownAt string `json:"lastShutdownAt"`
}

func TestShutdownHandlerSuccess(t *testing.T) {
	keyFile := genTestKey(t, t.TempDir())
	port := fakeSSHServer(t, "ok")
	oldPort := sshPort
	sshPort = port
	defer func() { sshPort = oldPort }()

	srv := Server{ID: 1, Name: "NAS", IP: "127.0.0.1", Port: 22, Check: "tcp",
		SshUser: "root", SshKeyPath: keyFile}
	r, st := newTestBackend(t, srv)

	// Before any action: the new status fields are present, lastShutdownAt
	// empty (the "never" view path, no check has run yet).
	items := doStatus(t, r)
	if len(items) != 1 || items[0].SshUser != "root" || items[0].SshKeyPath != keyFile {
		t.Fatalf("status before: %+v", items)
	}
	if items[0].LastShutdownAt != "" {
		t.Fatalf("lastShutdownAt before = %q, want empty", items[0].LastShutdownAt)
	}

	w := doShutdown(t, r)
	if w.Code != http.StatusOK {
		t.Fatalf("status = %d (body %s)", w.Code, w.Body.String())
	}
	var resp shutdownResp
	if err := json.Unmarshal(w.Body.Bytes(), &resp); err != nil {
		t.Fatalf("body: %v", err)
	}
	if !resp.OK {
		t.Fatalf("ok = false (body %s)", w.Body.String())
	}
	if resp.LastShutdownAt == "" {
		t.Fatalf("lastShutdownAt empty in response")
	}

	// The attempt is visible in /api/status once a health check has run
	// (the zero "never" view, like lastWolAt, carries no timestamps — the
	// check loop repopulates the view, mirroring the WOL behaviour).
	st.setStatus(srv, true, 3, time.Now().UTC().Format(time.RFC3339))
	items = doStatus(t, r)
	if items[0].LastShutdownAt != resp.LastShutdownAt {
		t.Fatalf("status lastShutdownAt = %q, want %q", items[0].LastShutdownAt, resp.LastShutdownAt)
	}
}

func TestShutdownHandlerConnectionDrop(t *testing.T) {
	keyFile := genTestKey(t, t.TempDir())
	port := fakeSSHServer(t, "drop")
	oldPort := sshPort
	sshPort = port
	defer func() { sshPort = oldPort }()

	srv := Server{ID: 1, Name: "NAS", IP: "127.0.0.1", Port: 22, Check: "tcp",
		SshUser: "root", SshKeyPath: keyFile}
	r, _ := newTestBackend(t, srv)

	// The host "powers off" mid-session: the connection dies right after
	// sshd accepted the command. That is still a success.
	w := doShutdown(t, r)
	if w.Code != http.StatusOK {
		t.Fatalf("status = %d (body %s)", w.Code, w.Body.String())
	}
	var resp shutdownResp
	if err := json.Unmarshal(w.Body.Bytes(), &resp); err != nil {
		t.Fatalf("body: %v", err)
	}
	if !resp.OK {
		t.Fatalf("ok = false after connection drop (body %s)", w.Body.String())
	}
}

func TestShutdownHandlerCommandFailure(t *testing.T) {
	keyFile := genTestKey(t, t.TempDir())
	port := fakeSSHServer(t, "fail")
	oldPort := sshPort
	sshPort = port
	defer func() { sshPort = oldPort }()

	srv := Server{ID: 1, Name: "NAS", IP: "127.0.0.1", Port: 22, Check: "tcp",
		SshUser: "root", SshKeyPath: keyFile}
	r, _ := newTestBackend(t, srv)

	w := doShutdown(t, r)
	if w.Code != http.StatusOK {
		t.Fatalf("status = %d (body %s)", w.Code, w.Body.String())
	}
	var resp shutdownResp
	if err := json.Unmarshal(w.Body.Bytes(), &resp); err != nil {
		t.Fatalf("body: %v", err)
	}
	if resp.OK {
		t.Fatalf("ok = true for a failed command (body %s)", w.Body.String())
	}
	if !strings.Contains(resp.Message, "poweroff command failed") || !strings.Contains(resp.Message, "systemctl: not found") {
		t.Fatalf("message = %q", resp.Message)
	}
	// A failed attempt is still recorded.
	if resp.LastShutdownAt == "" {
		t.Fatalf("lastShutdownAt empty after failed attempt")
	}
}

func TestShutdownHandlerUnreachable(t *testing.T) {
	keyFile := genTestKey(t, t.TempDir())
	// A port with no listener: the dial fails fast.
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	deadPort := ln.Addr().(*net.TCPAddr).Port
	ln.Close()
	oldPort := sshPort
	sshPort = deadPort
	defer func() { sshPort = oldPort }()

	srv := Server{ID: 1, Name: "NAS", IP: "127.0.0.1", Port: 22, Check: "tcp",
		SshUser: "root", SshKeyPath: keyFile}
	r, _ := newTestBackend(t, srv)

	w := doShutdown(t, r)
	var resp shutdownResp
	if err := json.Unmarshal(w.Body.Bytes(), &resp); err != nil {
		t.Fatalf("body: %v", err)
	}
	if resp.OK {
		t.Fatalf("ok = true for an unreachable server (body %s)", w.Body.String())
	}
	if !strings.Contains(resp.Message, "unreachable or OS not responsive") {
		t.Fatalf("message = %q", resp.Message)
	}
}

func TestShutdownHandlerKeyProblems(t *testing.T) {
	// Missing key file.
	port := fakeSSHServer(t, "ok")
	oldPort := sshPort
	sshPort = port
	defer func() { sshPort = oldPort }()

	srv := Server{ID: 1, Name: "NAS", IP: "127.0.0.1", Port: 22, Check: "tcp",
		SshUser: "root", SshKeyPath: "/nonexistent/key"}
	r, st := newTestBackend(t, srv)

	w := doShutdown(t, r)
	var resp shutdownResp
	if err := json.Unmarshal(w.Body.Bytes(), &resp); err != nil {
		t.Fatalf("body: %v", err)
	}
	if resp.OK || !strings.Contains(resp.Message, "cannot read ssh key") {
		t.Fatalf("resp = %+v (body %s)", resp, w.Body.String())
	}

	// Invalid key file (not a private key).
	badDir := t.TempDir()
	if err := os.WriteFile(badDir+"/bad_key", []byte("not a key"), 0600); err != nil {
		t.Fatal(err)
	}
	st.mu.Lock()
	st.servers[0].SshKeyPath = badDir + "/bad_key"
	st.mu.Unlock()
	w = doShutdown(t, r)
	resp = shutdownResp{}
	if err := json.Unmarshal(w.Body.Bytes(), &resp); err != nil {
		t.Fatalf("body: %v", err)
	}
	if resp.OK || !strings.Contains(resp.Message, "invalid ssh key") {
		t.Fatalf("resp = %+v (body %s)", resp, w.Body.String())
	}
}

func TestShutdownHandlerAuthFailure(t *testing.T) {
	keyFile := genTestKey(t, t.TempDir())
	port := fakeSSHServer(t, "authfail")
	oldPort := sshPort
	sshPort = port
	defer func() { sshPort = oldPort }()

	srv := Server{ID: 1, Name: "NAS", IP: "127.0.0.1", Port: 22, Check: "tcp",
		SshUser: "root", SshKeyPath: keyFile}
	r, _ := newTestBackend(t, srv)

	w := doShutdown(t, r)
	var resp shutdownResp
	if err := json.Unmarshal(w.Body.Bytes(), &resp); err != nil {
		t.Fatalf("body: %v", err)
	}
	if resp.OK || !strings.Contains(resp.Message, "ssh authentication failed") {
		t.Fatalf("resp = %+v (body %s)", resp, w.Body.String())
	}
}

func TestShutdownHandlerGating(t *testing.T) {
	// No SSH config: 400, like the empty-MAC WOL case.
	srv := Server{ID: 1, Name: "NAS", IP: "127.0.0.1", Port: 22, Check: "tcp"}
	r, _ := newTestBackend(t, srv)

	w := doShutdown(t, r)
	if w.Code != http.StatusBadRequest {
		t.Fatalf("status = %d (body %s)", w.Code, w.Body.String())
	}
	var resp shutdownResp
	if err := json.Unmarshal(w.Body.Bytes(), &resp); err != nil {
		t.Fatalf("body: %v", err)
	}
	if resp.OK || !strings.Contains(resp.Message, "no ssh shutdown configured") {
		t.Fatalf("resp = %+v (body %s)", resp, w.Body.String())
	}

	// Unknown server: 404.
	w = httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPost, "/api/servers/99/shutdown", nil)
	r.ServeHTTP(w, req)
	if w.Code != http.StatusNotFound {
		t.Fatalf("status = %d (body %s)", w.Code, w.Body.String())
	}
}
