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
	"sync/atomic"
	"testing"
	"time"

	"github.com/gin-gonic/gin"
	"golang.org/x/crypto/ssh"
)

// testPassword is the password the in-process SSH servers accept.
const testPassword = "secret"

// ---------- pure helpers ----------

func TestNormalizeSSHFields(t *testing.T) {
	// All SSH fields set: accepted and trimmed (poweroff command included).
	sv := Server{Name: "NAS", IP: "192.168.1.10", SshUser: "  root  ", SshKeyPath: "  ~/keys/polex  ", SshPassword: "  s3cret  ", PoweroffCommand: "  sudo shutdown -h now  "}
	if err := normalize(&sv); err != nil {
		t.Fatalf("normalize: %v", err)
	}
	if sv.SshUser != "root" || sv.SshKeyPath != "~/keys/polex" || sv.SshPassword != "s3cret" || sv.PoweroffCommand != "sudo shutdown -h now" {
		t.Fatalf("ssh fields not trimmed: %q %q %q %q", sv.SshUser, sv.SshKeyPath, sv.SshPassword, sv.PoweroffCommand)
	}

	// Poweroff command without SSH credentials: accepted (it is the
	// command override, not a credential) — power-off stays disabled by
	// the user+credential rule below.
	sv = Server{Name: "NAS", IP: "192.168.1.10", PoweroffCommand: "shutdown -h now"}
	if err := normalize(&sv); err != nil {
		t.Fatalf("normalize (poweroff command only): %v", err)
	}

	// User + password only (no key): accepted.
	sv = Server{Name: "NAS", IP: "192.168.1.10", SshUser: "root", SshPassword: "secret"}
	if err := normalize(&sv); err != nil {
		t.Fatalf("normalize (user + password): %v", err)
	}
	if sv.SshKeyPath != "" {
		t.Fatalf("key path should stay empty, got %q", sv.SshKeyPath)
	}

	// Neither set: fine (shutdown disabled).
	sv = Server{Name: "NAS", IP: "192.168.1.10"}
	if err := normalize(&sv); err != nil {
		t.Fatalf("normalize (no ssh): %v", err)
	}

	// Any incomplete subset: rejected.
	for _, sv := range []Server{
		{Name: "NAS", IP: "192.168.1.10", SshUser: "root"},                         // user, no credential
		{Name: "NAS", IP: "192.168.1.10", SshKeyPath: "/keys/polex"},               // key without user
		{Name: "NAS", IP: "192.168.1.10", SshPassword: "secret"},                   // password without user
		{Name: "NAS", IP: "192.168.1.10", SshKeyPath: "/k", SshPassword: "secret"}, // credentials without user
	} {
		if err := normalize(&sv); err == nil {
			t.Fatalf("normalize: incomplete ssh config accepted (user=%q key=%q pass=%q)", sv.SshUser, sv.SshKeyPath, sv.SshPassword)
		}
	}

	// The two rejection directions get distinct, honest errors.
	if err := normalize(&Server{Name: "NAS", IP: "192.168.1.10", SshUser: "root"}); !strings.Contains(err.Error(), "sshKeyPath or sshPassword must be set") {
		t.Errorf("user-only error = %v", err)
	}
	if err := normalize(&Server{Name: "NAS", IP: "192.168.1.10", SshPassword: "secret"}); !strings.Contains(err.Error(), "sshUser must be set") {
		t.Errorf("credential-only error = %v", err)
	}
}

func TestSshConfigured(t *testing.T) {
	cases := []struct {
		user, key, pass string
		want            bool
	}{
		{"", "", "", false},
		{"root", "", "", false}, // user alone is not enough
		{"", "/keys/polex", "", false},
		{"", "", "secret", false},
		{"", "/keys/polex", "secret", false}, // credentials without a user
		{"root", "/keys/polex", "", true},
		{"root", "", "secret", true},
		{"root", "/keys/polex", "secret", true},
	}
	for _, c := range cases {
		if got := (Server{SshUser: c.user, SshKeyPath: c.key, SshPassword: c.pass}).sshConfigured(); got != c.want {
			t.Errorf("sshConfigured(%q, %q, %q) = %v, want %v", c.user, c.key, c.pass, got, c.want)
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

func TestPoweroffCommandFor(t *testing.T) {
	// Empty/unset → the default; a set value → sent verbatim.
	if got := poweroffCommandFor(""); got != shutdownCommand {
		t.Errorf("poweroffCommandFor(\"\") = %q, want the default %q", got, shutdownCommand)
	}
	if got := poweroffCommandFor("shutdown -h now"); got != "shutdown -h now" {
		t.Errorf("poweroffCommandFor(override) = %q, want the override verbatim", got)
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

// authRecorder records which credential types a client offered during the
// handshake, so tests can assert the key-then-password order (and that a
// successful key never burns a password attempt), plus the command the
// client started via exec.
type authRecorder struct {
	keyOffered      atomic.Bool
	passwordOffered atomic.Bool
	execCommand     atomic.Value // holds the started exec command (string)
}

// fakeSSHServer starts an in-process SSH server on 127.0.0.1 that answers
// the `sudo systemctl poweroff` exec request per mode and returns its port
// plus a recorder of the offered credentials and the started command:
//
//	"ok"         — accept any key; exit status 0 (clean acceptance)
//	"fail"       — accept any key; stderr + exit status 127
//	"drop"       — accept key, then drop the TCP connection
//	             (simulates the host powering off mid-session)
//	"authfail"   — reject the key during authentication
//	"hang"       — accept key; handshake completes, then the channel-open
//	             request is never answered (half-wedged host)
//	"stall"      — accept key; accept the exec request, then neither the
//	             exit status nor the connection ever comes back (slow host)
//	"pwok"       — reject the key; accept password testPassword; exit 0
//	             (the key-rejected → password fallback path)
//	"pwauthfail" — reject the key AND the password (all credentials fail)
func fakeSSHServer(t *testing.T, mode string) (int, *authRecorder) {
	t.Helper()
	_, hostPriv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatalf("host key: %v", err)
	}
	hostSigner, err := ssh.NewSignerFromSigner(hostPriv)
	if err != nil {
		t.Fatalf("host signer: %v", err)
	}

	rec := &authRecorder{}
	cfg := &ssh.ServerConfig{}
	cfg.AddHostKey(hostSigner)
	cfg.PublicKeyCallback = func(ssh.ConnMetadata, ssh.PublicKey) (*ssh.Permissions, error) {
		rec.keyOffered.Store(true)
		if mode == "authfail" || mode == "pwok" || mode == "pwauthfail" {
			return nil, fmt.Errorf("key rejected")
		}
		return nil, nil // accept any key
	}
	cfg.PasswordCallback = func(_ ssh.ConnMetadata, pass []byte) (*ssh.Permissions, error) {
		rec.passwordOffered.Store(true)
		if mode == "pwok" && string(pass) == testPassword {
			return nil, nil
		}
		return nil, fmt.Errorf("password rejected")
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
				if mode == "hang" {
					// Half-wedged host: never answer the channel-open
					// request.
					for range chans {
					}
					return
				}
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
						// The exec payload is an SSH string: 4-byte length + bytes.
						if len(req.Payload) >= 4 {
							rec.execCommand.Store(string(req.Payload[4:]))
						}
						switch mode {
						case "fail":
							_, _ = ch.Stderr().Write([]byte("sh: systemctl: not found\n"))
							_, _ = ch.SendRequest("exit-status", false, ssh.Marshal(struct {
								Status uint32
							}{127}))
						case "ok":
							_, _ = ch.SendRequest("exit-status", false, ssh.Marshal(struct {
								Status uint32
							}{0}))
						}
						if mode == "stall" {
							// The exit status never comes; hold the
							// connection open until the client's deadline
							// ends the wait.
							select {}
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
	return ln.Addr().(*net.TCPAddr).Port, rec
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
	port, rec := fakeSSHServer(t, "ok")
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
	if got := rec.execCommand.Load(); got != "sudo systemctl poweroff" {
		t.Fatalf("started command = %q, want %q", got, "sudo systemctl poweroff")
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
	port, rec := fakeSSHServer(t, "drop")
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
	if got := rec.execCommand.Load(); got != "sudo systemctl poweroff" {
		t.Fatalf("started command = %q, want %q", got, "sudo systemctl poweroff")
	}
}

func TestShutdownHandlerCommandFailure(t *testing.T) {
	keyFile := genTestKey(t, t.TempDir())
	port, rec := fakeSSHServer(t, "fail")
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
	if got := rec.execCommand.Load(); got != "sudo systemctl poweroff" {
		t.Fatalf("started command = %q, want %q", got, "sudo systemctl poweroff")
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
	// A key file that fails to load is a failed key: with a password set,
	// the shutdown proceeds with the password alone and the key error
	// stays visible in the result; with no password set it is the
	// distinct local-config error.
	badDir := t.TempDir()
	if err := os.WriteFile(badDir+"/bad_key", []byte("not a key"), 0600); err != nil {
		t.Fatal(err)
	}
	oldPort := sshPort
	defer func() { sshPort = oldPort }()

	srv := Server{ID: 1, Name: "NAS", IP: "127.0.0.1", Port: 22, Check: "tcp",
		SshUser: "root", SshKeyPath: "/nonexistent/key"}
	r, st := newTestBackend(t, srv)
	var resp shutdownResp

	// Missing key file, no password: the hard local-config error.
	port, _ := fakeSSHServer(t, "ok")
	sshPort = port
	w := doShutdown(t, r)
	if err := json.Unmarshal(w.Body.Bytes(), &resp); err != nil {
		t.Fatalf("body: %v", err)
	}
	if resp.OK || !strings.Contains(resp.Message, "cannot read ssh key") {
		t.Fatalf("resp = %+v (body %s)", resp, w.Body.String())
	}

	// Missing key file, password set: password-only auth succeeds and the
	// key error stays visible in the result.
	port, rec := fakeSSHServer(t, "pwok")
	sshPort = port
	st.mu.Lock()
	st.servers[0].SshPassword = testPassword
	st.mu.Unlock()
	w = doShutdown(t, r)
	resp = shutdownResp{}
	if err := json.Unmarshal(w.Body.Bytes(), &resp); err != nil {
		t.Fatalf("body: %v", err)
	}
	if !resp.OK || !strings.Contains(resp.Message, "cannot read ssh key") {
		t.Fatalf("resp = %+v (body %s)", resp, w.Body.String())
	}
	if rec.keyOffered.Load() {
		t.Errorf("a key was offered although the key file could not be read")
	}
	if !rec.passwordOffered.Load() {
		t.Errorf("password was not offered")
	}

	// Invalid key file, no password: the hard local-config error.
	st.mu.Lock()
	st.servers[0] = Server{ID: 1, Name: "NAS", IP: "127.0.0.1", Port: 22, Check: "tcp",
		SshUser: "root", SshKeyPath: badDir + "/bad_key"}
	st.mu.Unlock()
	w = doShutdown(t, r)
	resp = shutdownResp{}
	if err := json.Unmarshal(w.Body.Bytes(), &resp); err != nil {
		t.Fatalf("body: %v", err)
	}
	if resp.OK || !strings.Contains(resp.Message, "invalid ssh key") {
		t.Fatalf("resp = %+v (body %s)", resp, w.Body.String())
	}

	// Invalid key file, password set: password-only auth succeeds and the
	// key error stays visible in the result.
	port, rec = fakeSSHServer(t, "pwok")
	sshPort = port
	st.mu.Lock()
	st.servers[0].SshPassword = testPassword
	st.mu.Unlock()
	w = doShutdown(t, r)
	resp = shutdownResp{}
	if err := json.Unmarshal(w.Body.Bytes(), &resp); err != nil {
		t.Fatalf("body: %v", err)
	}
	if !resp.OK || !strings.Contains(resp.Message, "invalid ssh key") {
		t.Fatalf("resp = %+v (body %s)", resp, w.Body.String())
	}
	if rec.keyOffered.Load() {
		t.Errorf("a key was offered although the key file is invalid")
	}
	if !rec.passwordOffered.Load() {
		t.Errorf("password was not offered")
	}

	// Broken key AND a rejected password: the auth-failure error is
	// reported after the only usable credential was tried, with the key
	// error kept visible.
	port, _ = fakeSSHServer(t, "pwauthfail")
	sshPort = port
	w = doShutdown(t, r)
	resp = shutdownResp{}
	if err := json.Unmarshal(w.Body.Bytes(), &resp); err != nil {
		t.Fatalf("body: %v", err)
	}
	if resp.OK || !strings.Contains(resp.Message, "ssh authentication failed") ||
		!strings.Contains(resp.Message, "invalid ssh key") {
		t.Fatalf("resp = %+v (body %s)", resp, w.Body.String())
	}

	// Broken key, password set, host unreachable: the non-auth error is
	// reported with the bare key error — no claim that the password was
	// used, since no connection completed.
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	deadPort := ln.Addr().(*net.TCPAddr).Port
	ln.Close()
	sshPort = deadPort
	w = doShutdown(t, r)
	resp = shutdownResp{}
	if err := json.Unmarshal(w.Body.Bytes(), &resp); err != nil {
		t.Fatalf("body: %v", err)
	}
	if resp.OK || !strings.Contains(resp.Message, "unreachable or OS not responsive") ||
		!strings.Contains(resp.Message, "invalid ssh key") {
		t.Fatalf("resp = %+v (body %s)", resp, w.Body.String())
	}
	if strings.Contains(resp.Message, "password used") {
		t.Errorf("message claims the password was used although no connection completed: %q", resp.Message)
	}
}

func TestShutdownHandlerAuthFailure(t *testing.T) {
	keyFile := genTestKey(t, t.TempDir())
	port, _ := fakeSSHServer(t, "authfail")
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

// TestShutdownHandlerKeySucceedsNoPasswordAttempt: key set AND password
// set, sshd accepts the key — the shutdown succeeds and the password is
// never offered (a successful key must not burn the fallback).
func TestShutdownHandlerKeySucceedsNoPasswordAttempt(t *testing.T) {
	keyFile := genTestKey(t, t.TempDir())
	port, rec := fakeSSHServer(t, "ok")
	oldPort := sshPort
	sshPort = port
	defer func() { sshPort = oldPort }()

	srv := Server{ID: 1, Name: "NAS", IP: "127.0.0.1", Port: 22, Check: "tcp",
		SshUser: "root", SshKeyPath: keyFile, SshPassword: testPassword}
	r, _ := newTestBackend(t, srv)

	w := doShutdown(t, r)
	var resp shutdownResp
	if err := json.Unmarshal(w.Body.Bytes(), &resp); err != nil {
		t.Fatalf("body: %v", err)
	}
	if !resp.OK {
		t.Fatalf("ok = false (body %s)", w.Body.String())
	}
	if !rec.keyOffered.Load() {
		t.Errorf("key was not offered to the server")
	}
	if rec.passwordOffered.Load() {
		t.Errorf("password was offered although the key was accepted")
	}
}

// TestShutdownHandlerKeyRejectedPasswordFallback: key set, sshd rejects
// the key (an authentication failure), password set — the password is
// offered next on the same connection and the shutdown succeeds.
func TestShutdownHandlerKeyRejectedPasswordFallback(t *testing.T) {
	keyFile := genTestKey(t, t.TempDir())
	port, rec := fakeSSHServer(t, "pwok")
	oldPort := sshPort
	sshPort = port
	defer func() { sshPort = oldPort }()

	srv := Server{ID: 1, Name: "NAS", IP: "127.0.0.1", Port: 22, Check: "tcp",
		SshUser: "root", SshKeyPath: keyFile, SshPassword: testPassword}
	r, _ := newTestBackend(t, srv)

	w := doShutdown(t, r)
	var resp shutdownResp
	if err := json.Unmarshal(w.Body.Bytes(), &resp); err != nil {
		t.Fatalf("body: %v", err)
	}
	if !resp.OK {
		t.Fatalf("ok = false after the password fallback (body %s)", w.Body.String())
	}
	if !strings.Contains(resp.Message, "shutdown command accepted") {
		t.Fatalf("message = %q", resp.Message)
	}
	if !rec.keyOffered.Load() {
		t.Errorf("key was not offered (it must be tried first)")
	}
	if !rec.passwordOffered.Load() {
		t.Errorf("password was not offered after the key rejection")
	}
}

// TestShutdownHandlerPasswordDirect: key path empty, password set —
// password auth is used directly (no key offered).
func TestShutdownHandlerPasswordDirect(t *testing.T) {
	port, rec := fakeSSHServer(t, "pwok")
	oldPort := sshPort
	sshPort = port
	defer func() { sshPort = oldPort }()

	srv := Server{ID: 1, Name: "NAS", IP: "127.0.0.1", Port: 22, Check: "tcp",
		SshUser: "root", SshPassword: testPassword}
	r, _ := newTestBackend(t, srv)

	w := doShutdown(t, r)
	var resp shutdownResp
	if err := json.Unmarshal(w.Body.Bytes(), &resp); err != nil {
		t.Fatalf("body: %v", err)
	}
	if !resp.OK {
		t.Fatalf("ok = false (body %s)", w.Body.String())
	}
	if rec.keyOffered.Load() {
		t.Errorf("a key was offered although no key path is configured")
	}
	if !rec.passwordOffered.Load() {
		t.Errorf("password was not offered")
	}
}

// TestShutdownHandlerAuthFailureAfterAllCreds: key AND password set, sshd
// rejects both — the auth-failed error is reported only after every
// configured credential was tried.
func TestShutdownHandlerAuthFailureAfterAllCreds(t *testing.T) {
	keyFile := genTestKey(t, t.TempDir())
	port, rec := fakeSSHServer(t, "pwauthfail")
	oldPort := sshPort
	sshPort = port
	defer func() { sshPort = oldPort }()

	srv := Server{ID: 1, Name: "NAS", IP: "127.0.0.1", Port: 22, Check: "tcp",
		SshUser: "root", SshKeyPath: keyFile, SshPassword: testPassword}
	r, _ := newTestBackend(t, srv)

	w := doShutdown(t, r)
	var resp shutdownResp
	if err := json.Unmarshal(w.Body.Bytes(), &resp); err != nil {
		t.Fatalf("body: %v", err)
	}
	if resp.OK || !strings.Contains(resp.Message, "ssh authentication failed") {
		t.Fatalf("resp = %+v (body %s)", resp, w.Body.String())
	}
	if !rec.keyOffered.Load() || !rec.passwordOffered.Load() {
		t.Errorf("expected both credentials to be tried (key=%v password=%v)", rec.keyOffered.Load(), rec.passwordOffered.Load())
	}
}

// TestShutdownHandlerNonAuthFailureNoFallback: key + password set, but the
// key attempt fails for a NON-auth reason (server unreachable — the dial
// never connects). The password fallback is for authentication failures
// only and must not be consumed: the error is the honest
// unreachable/OS-not-responsive one.
func TestShutdownHandlerNonAuthFailureNoFallback(t *testing.T) {
	keyFile := genTestKey(t, t.TempDir())
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
		SshUser: "root", SshKeyPath: keyFile, SshPassword: testPassword}
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
		t.Fatalf("message = %q (want the non-auth failure, not the password fallback)", resp.Message)
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

	// A user with no credential (neither key nor password) is also "not
	// configured" for the predicate — such an entry can only exist if the
	// config bypassed normalize().
	st2 := &state{
		servers:      []Server{{ID: 1, Name: "NAS", IP: "127.0.0.1", Port: 22, Check: "tcp", SshUser: "root"}},
		status:       map[int]StatusView{},
		lastWol:      map[int]string{},
		lastShutdown: map[int]string{},
		nextID:       2,
	}
	r2 := gin.New()
	r2.POST("/api/servers/:id/shutdown", st2.shutdown)
	w = httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPost, "/api/servers/1/shutdown", nil)
	r2.ServeHTTP(w, req)
	if w.Code != http.StatusBadRequest {
		t.Fatalf("status = %d (body %s)", w.Code, w.Body.String())
	}
	resp = shutdownResp{}
	if err := json.Unmarshal(w.Body.Bytes(), &resp); err != nil {
		t.Fatalf("body: %v", err)
	}
	if resp.OK || !strings.Contains(resp.Message, "no ssh shutdown configured") {
		t.Fatalf("resp = %+v (body %s)", resp, w.Body.String())
	}

	// Unknown server: 404.
	w = httptest.NewRecorder()
	req = httptest.NewRequest(http.MethodPost, "/api/servers/99/shutdown", nil)
	r.ServeHTTP(w, req)
	if w.Code != http.StatusNotFound {
		t.Fatalf("status = %d (body %s)", w.Code, w.Body.String())
	}
}

func TestShutdownHandlerHalfWedgedHost(t *testing.T) {
	// The handshake completes but the channel-open request is never
	// answered: the handler must still answer with an honest error,
	// inside the total deadline.
	keyFile := genTestKey(t, t.TempDir())
	port, _ := fakeSSHServer(t, "hang")
	oldPort := sshPort
	sshPort = port
	defer func() { sshPort = oldPort }()
	oldTotal := sshTotalTimeout
	sshTotalTimeout = 500 * time.Millisecond
	defer func() { sshTotalTimeout = oldTotal }()

	srv := Server{ID: 1, Name: "NAS", IP: "127.0.0.1", Port: 22, Check: "tcp",
		SshUser: "root", SshKeyPath: keyFile}
	r, _ := newTestBackend(t, srv)

	start := time.Now()
	w := doShutdown(t, r)
	if elapsed := time.Since(start); elapsed > sshTotalTimeout+time.Second {
		t.Fatalf("handler took %v: total deadline not enforced", elapsed)
	}
	var resp shutdownResp
	if err := json.Unmarshal(w.Body.Bytes(), &resp); err != nil {
		t.Fatalf("body: %v", err)
	}
	if resp.OK {
		t.Fatalf("ok = true for a half-wedged host (body %s)", w.Body.String())
	}
	if !strings.Contains(resp.Message, "unreachable or OS not responsive") {
		t.Fatalf("message = %q", resp.Message)
	}
}

func TestShutdownHandlerSlowConfirm(t *testing.T) {
	// The exec is accepted but the exit status never arrives (slow
	// systemd): still a success, and the wait must stay inside the total
	// deadline.
	keyFile := genTestKey(t, t.TempDir())
	port, rec := fakeSSHServer(t, "stall")
	oldPort := sshPort
	sshPort = port
	defer func() { sshPort = oldPort }()
	oldTotal := sshTotalTimeout
	sshTotalTimeout = 500 * time.Millisecond
	defer func() { sshTotalTimeout = oldTotal }()

	srv := Server{ID: 1, Name: "NAS", IP: "127.0.0.1", Port: 22, Check: "tcp",
		SshUser: "root", SshKeyPath: keyFile}
	r, _ := newTestBackend(t, srv)

	start := time.Now()
	w := doShutdown(t, r)
	if elapsed := time.Since(start); elapsed > sshTotalTimeout+time.Second {
		t.Fatalf("handler took %v: total deadline not enforced", elapsed)
	}
	var resp shutdownResp
	if err := json.Unmarshal(w.Body.Bytes(), &resp); err != nil {
		t.Fatalf("body: %v", err)
	}
	if !resp.OK || !strings.Contains(resp.Message, "did not confirm") {
		t.Fatalf("resp = %+v (body %s)", resp, w.Body.String())
	}
	if got := rec.execCommand.Load(); got != "sudo systemctl poweroff" {
		t.Fatalf("started command = %q, want %q", got, "sudo systemctl poweroff")
	}
}

// TestShutdownHandlerPoweroffCommand: the per-server poweroffCommand field
// is sent verbatim over the wire when set, and an empty field sends the
// default. The field also rides in GET /api/status.
func TestShutdownHandlerPoweroffCommand(t *testing.T) {
	keyFile := genTestKey(t, t.TempDir())
	port, rec := fakeSSHServer(t, "ok")
	oldPort := sshPort
	sshPort = port
	defer func() { sshPort = oldPort }()

	srv := Server{ID: 1, Name: "NAS", IP: "127.0.0.1", Port: 22, Check: "tcp",
		SshUser: "root", SshKeyPath: keyFile}
	r, st := newTestBackend(t, srv)

	// Empty field: the default command goes over the wire, and the field
	// shows up (empty) in /api/status.
	w := doShutdown(t, r)
	var resp shutdownResp
	if err := json.Unmarshal(w.Body.Bytes(), &resp); err != nil {
		t.Fatalf("body: %v", err)
	}
	if !resp.OK {
		t.Fatalf("ok = false (body %s)", w.Body.String())
	}
	if got := rec.execCommand.Load(); got != shutdownCommand {
		t.Fatalf("started command = %q, want the default %q", got, shutdownCommand)
	}
	items := doStatus(t, r)
	if len(items) != 1 || items[0].PoweroffCommand != "" {
		t.Fatalf("status poweroffCommand before = %+v, want empty", items)
	}

	// Explicit field: exactly that command goes over the wire.
	override := "echo polex poweroff"
	st.mu.Lock()
	st.servers[0].PoweroffCommand = override
	st.mu.Unlock()
	w = doShutdown(t, r)
	resp = shutdownResp{}
	if err := json.Unmarshal(w.Body.Bytes(), &resp); err != nil {
		t.Fatalf("body: %v", err)
	}
	if !resp.OK {
		t.Fatalf("ok = false (body %s)", w.Body.String())
	}
	if got := rec.execCommand.Load(); got != override {
		t.Fatalf("started command = %q, want the explicit %q", got, override)
	}

	// And the value is visible in /api/status.
	items = doStatus(t, r)
	if items[0].PoweroffCommand != override {
		t.Fatalf("status poweroffCommand = %q, want %q", items[0].PoweroffCommand, override)
	}
}
