package main

import (
	"context"
	"encoding/json"
	"log"
	"math"
	"net"
	"os"
	"os/exec"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/gin-gonic/gin"
)

// ---------- models ----------

type Server struct {
	ID          int    `json:"id"`
	Name        string `json:"name"`
	IP          string `json:"ip"`
	MAC         string `json:"mac"`         // empty = WOL disabled
	Port        int    `json:"port"`        // TCP check port (ignored when Check == "ping")
	Check       string `json:"check"`       // "tcp" (default) or "ping"
	SshUser     string `json:"sshUser"`     // + key/password below: SSH power-off (user + ≥1 credential = enabled)
	SshKeyPath  string `json:"sshKeyPath"`  // path to the private key on the backend host
	SshPassword string `json:"sshPassword"` // password auth; fallback after a rejected key (or direct when no key)
}

type StatusView struct {
	ID             int    `json:"id"`
	Name           string `json:"name"`
	IP             string `json:"ip"`
	MAC            string `json:"mac"`
	Port           int    `json:"port"`
	Check          string `json:"check"`
	SshUser        string `json:"sshUser"`
	SshKeyPath     string `json:"sshKeyPath"`
	SshPassword    string `json:"sshPassword"`
	Online         bool   `json:"online"`
	LatencyMs      int64  `json:"latencyMs"`
	LastChecked    string `json:"lastChecked"`
	LastWolAt      string `json:"lastWolAt"`
	LastShutdownAt string `json:"lastShutdownAt"`
}

// ---------- state ----------

type state struct {
	mu           sync.RWMutex
	servers      []Server
	status       map[int]StatusView
	lastWol      map[int]string
	lastShutdown map[int]string
	nextID       int
	auth         authConfig
	sessions     *sessions
}

const dataFile = "data.json"

func newDefaultServers() []Server {
	return []Server{
		{Name: "NAS", IP: "192.168.1.10", MAC: "AA:BB:CC:DD:EE:01", Port: 22, Check: "tcp"},
		{Name: "Workstation", IP: "192.168.1.11", MAC: "AA:BB:CC:DD:EE:02", Port: 22, Check: "tcp"},
	}
}

func (s *state) load() {
	s.status = map[int]StatusView{}
	s.lastWol = map[int]string{}
	s.lastShutdown = map[int]string{}
	b, err := os.ReadFile(dataFile)
	if err != nil {
		// first run: seed a couple of example servers
		s.servers = newDefaultServers()
		for i := range s.servers {
			s.servers[i].ID = i + 1
		}
		s.nextID = len(s.servers) + 1
		s.save()
		return
	}
	var d struct {
		Servers []Server `json:"servers"`
		NextID  int      `json:"nextId"`
	}
	if err := json.Unmarshal(b, &d); err != nil {
		log.Printf("warn: could not load %s: %v", dataFile, err)
		return
	}
	s.servers = d.Servers
	if d.NextID > len(s.servers) {
		s.nextID = d.NextID
	}
}

func (s *state) save() {
	s.mu.Lock()
	defer s.mu.Unlock()
	d := struct {
		Servers []Server `json:"servers"`
		NextID  int      `json:"nextId"`
	}{Servers: s.servers, NextID: s.nextID}
	b, err := json.MarshalIndent(d, "", "  ")
	if err != nil {
		log.Printf("warn: could not marshal config: %v", err)
		return
	}
	// Atomic write: temp file in the same directory + rename, so a crash
	// mid-write never leaves a truncated data.json behind.
	tmp := dataFile + ".tmp"
	if err := os.WriteFile(tmp, b, 0644); err != nil {
		log.Printf("warn: could not write %s: %v", tmp, err)
		return
	}
	if err := os.Rename(tmp, dataFile); err != nil {
		log.Printf("warn: could not rename %s -> %s: %v", tmp, dataFile, err)
	}
}

// ---------- health checking ----------

func (s *state) checkLoop(interval time.Duration) {
	for {
		time.Sleep(interval)
		s.checkAll()
	}
}

func (s *state) checkAll() {
	s.mu.RLock()
	servers := make([]Server, len(s.servers))
	copy(servers, s.servers)
	s.mu.RUnlock()

	var wg sync.WaitGroup
	for _, sv := range servers {
		wg.Add(1)
		go func(sv Server) {
			defer wg.Done()
			online, ms := checkServer(sv)
			s.setStatus(sv, online, ms, time.Now().UTC().Format(time.RFC3339))
		}(sv)
	}
	wg.Wait()
}

// checkServer runs the health check a server is configured for and reports
// (online, latencyMs). TCP servers do a port connect; ping servers do one
// ICMP echo.
func checkServer(sv Server) (bool, int64) {
	if sv.Check == "ping" {
		return pingHost(sv.IP)
	}
	return checkHost(sv.IP, sv.Port)
}

// checkHost does a TCP connect to ip:port; success = online.
func checkHost(ip string, port int) (bool, int64) {
	if port <= 0 {
		port = 22
	}
	start := time.Now()
	conn, err := net.DialTimeout("tcp", net.JoinHostPort(ip, strconv.Itoa(port)), 3*time.Second)
	if err != nil {
		return false, 0
	}
	_ = conn.Close()
	return true, time.Since(start).Milliseconds()
}

// pingHost sends a single ICMP echo to ip and reports (online, rttMs). It
// shells out to the system `ping` (typically setuid on Linux) because a
// Go process without CAP_NET_RAW / root can't open raw ICMP sockets; the
// setuid helper works for a normal user and inside the Docker container
// (NET_RAW is in Docker's default capability set).
var (
	pingOnce sync.Once
	pingPath string
)

// pingBinary resolves the `ping` path once, logging a warning if it is
// missing so a ping-based check fails visibly rather than silently.
func pingBinary() (string, bool) {
	pingOnce.Do(func() {
		if p, err := exec.LookPath("ping"); err == nil {
			pingPath = p
		} else {
			log.Printf("warn: 'ping' not found in PATH; ping health checks will report offline: %v", err)
		}
	})
	return pingPath, pingPath != ""
}

func pingHost(ip string) (bool, int64) {
	p, ok := pingBinary()
	if !ok {
		return false, 0
	}
	// -c 1: one packet; -W 3: ping gives up after ~3s with no reply. The
	// context timeout is a safety net so a check never outlives the interval.
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	out, err := exec.CommandContext(ctx, p, "-c", "1", "-W", "3", ip).Output()
	if err != nil {
		return false, 0 // no reply (or ping error) => offline
	}
	return true, parsePingTime(string(out))
}

var pingTimeRe = regexp.MustCompile(`time=([0-9]+(?:\.[0-9]+)?)\s*ms`)

// parsePingTime pulls the RTT (ms) out of ping's "time=X ms" field.
func parsePingTime(out string) int64 {
	m := pingTimeRe.FindStringSubmatch(out)
	if len(m) < 2 {
		return 0
	}
	v, _ := strconv.ParseFloat(m[1], 64)
	return int64(math.Round(v))
}

func (s *state) setStatus(sv Server, online bool, ms int64, when string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	v := StatusView{
		ID:             sv.ID,
		Name:           sv.Name,
		IP:             sv.IP,
		MAC:            sv.MAC,
		Port:           sv.Port,
		Check:          sv.Check,
		SshUser:        sv.SshUser,
		SshKeyPath:     sv.SshKeyPath,
		SshPassword:    sv.SshPassword,
		Online:         online,
		LatencyMs:      ms,
		LastChecked:    when,
		LastWolAt:      s.lastWol[sv.ID],
		LastShutdownAt: s.lastShutdown[sv.ID],
	}
	s.status[sv.ID] = v
}

func (s *state) find(id int) *Server {
	for i := range s.servers {
		if s.servers[i].ID == id {
			return &s.servers[i]
		}
	}
	return nil
}

func normalize(in *Server) error {
	in.Name = strings.TrimSpace(in.Name)
	in.IP = strings.TrimSpace(in.IP)
	in.MAC = strings.ToUpper(strings.TrimSpace(in.MAC))
	in.SshUser = strings.TrimSpace(in.SshUser)
	in.SshKeyPath = strings.TrimSpace(in.SshKeyPath)
	in.SshPassword = strings.TrimSpace(in.SshPassword)
	in.ID = 0
	if in.Name == "" || in.IP == "" {
		return errField("name and ip are required")
	}
	// SSH power-off is a user plus at least one credential (key path and/or
	// password); any other subset is a mistake (credentials without a user,
	// or a user with nothing to authenticate with).
	if (in.SshUser == "") != (in.SshKeyPath == "" && in.SshPassword == "") {
		if in.SshUser == "" {
			return errField("sshUser must be set when sshKeyPath or sshPassword is set")
		}
		return errField("sshKeyPath or sshPassword must be set when sshUser is set")
	}
	if net.ParseIP(in.IP) == nil {
		return errField("invalid ip: " + in.IP)
	}
	if in.MAC != "" && !validMac(in.MAC) {
		return errField("invalid mac address: " + in.MAC)
	}
	if in.Port <= 0 || in.Port > 65535 {
		in.Port = 22
	}
	in.Check = strings.ToLower(strings.TrimSpace(in.Check))
	switch in.Check {
	case "", "tcp", "ping":
	default:
		return errField("check must be 'tcp' or 'ping'")
	}
	if in.Check == "" {
		in.Check = "tcp"
	}
	return nil
}

type fieldError struct{ msg string }

func (e fieldError) Error() string { return e.msg }

func errField(msg string) error { return fieldError{msg} }

// ---------- handlers ----------

func (s *state) health(c *gin.Context) {
	c.JSON(200, gin.H{"ok": true, "time": time.Now().UTC().Format(time.RFC3339)})
}

func (s *state) listServers(c *gin.Context) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	c.JSON(200, s.servers)
}

func (s *state) listStatus(c *gin.Context) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	out := make([]StatusView, 0, len(s.servers))
	for _, sv := range s.servers {
		v, ok := s.status[sv.ID]
		if !ok {
			v = StatusView{ID: sv.ID, Name: sv.Name, IP: sv.IP, MAC: sv.MAC, Port: sv.Port, Check: sv.Check, SshUser: sv.SshUser, SshKeyPath: sv.SshKeyPath, SshPassword: sv.SshPassword, LastChecked: "never"}
		}
		out = append(out, v)
	}
	c.JSON(200, out)
}

func (s *state) addServer(c *gin.Context) {
	var in Server
	if err := s.bindAndValidate(c, &in); err != nil {
		return
	}
	s.mu.Lock()
	in.ID = s.nextID
	s.nextID++
	s.servers = append(s.servers, in)
	s.mu.Unlock()
	s.save()

	online, ms := checkServer(in)
	s.setStatus(in, online, ms, time.Now().UTC().Format(time.RFC3339))
	c.JSON(200, s.statusFor(in.ID))
}

func (s *state) updateServer(c *gin.Context) {
	id, err := strconv.Atoi(c.Param("id"))
	if err != nil {
		c.JSON(400, gin.H{"error": "bad id"})
		return
	}
	s.mu.RLock()
	if s.find(id) == nil {
		s.mu.RUnlock()
		c.JSON(404, gin.H{"error": "server not found"})
		return
	}
	s.mu.RUnlock()

	var in Server
	if err := s.bindAndValidate(c, &in); err != nil {
		return
	}
	in.ID = id
	s.mu.Lock()
	for i := range s.servers {
		if s.servers[i].ID == id {
			s.servers[i] = in
		}
	}
	s.mu.Unlock()
	s.save()

	online, ms := checkServer(in)
	s.setStatus(in, online, ms, time.Now().UTC().Format(time.RFC3339))
	c.JSON(200, s.statusFor(id))
}

func (s *state) deleteServer(c *gin.Context) {
	id, err := strconv.Atoi(c.Param("id"))
	if err != nil {
		c.JSON(400, gin.H{"error": "bad id"})
		return
	}
	s.mu.Lock()
	found := false
	out := make([]Server, 0, len(s.servers))
	for _, sv := range s.servers {
		if sv.ID == id {
			found = true
			continue
		}
		out = append(out, sv)
	}
	s.servers = out
	delete(s.status, id)
	delete(s.lastWol, id)
	delete(s.lastShutdown, id)
	s.mu.Unlock()
	if !found {
		c.JSON(404, gin.H{"error": "server not found"})
		return
	}
	s.save()
	c.JSON(200, gin.H{"ok": true})
}

// reorderServers sets the display order to the given list of IDs (the
// order the app's drag-and-drop produces). Any IDs missing from the body
// keep their current relative position at the end (defensive), so a
// partial list can't drop servers from the config.
func (s *state) reorderServers(c *gin.Context) {
	var in struct {
		Order []int `json:"order"`
	}
	if err := c.ShouldBindJSON(&in); err != nil {
		c.JSON(400, gin.H{"error": "invalid json: " + err.Error()})
		return
	}
	s.mu.Lock()
	byID := make(map[int]Server, len(s.servers))
	for _, sv := range s.servers {
		byID[sv.ID] = sv
	}
	out := make([]Server, 0, len(s.servers))
	seen := make(map[int]bool, len(in.Order))
	for _, id := range in.Order {
		if sv, ok := byID[id]; ok {
			out = append(out, sv)
			delete(byID, id)
			seen[id] = true
		}
	}
	// Append any servers not mentioned, preserving their current order.
	for _, sv := range s.servers {
		if !seen[sv.ID] {
			out = append(out, sv)
		}
	}
	s.servers = out
	s.mu.Unlock()
	s.save()
	c.JSON(200, gin.H{"ok": true})
}

// bindAndValidate binds the JSON body and validates fields. Writes the
// error response itself on failure.
func (s *state) bindAndValidate(c *gin.Context, in *Server) error {
	if err := c.ShouldBindJSON(in); err != nil {
		c.JSON(400, gin.H{"error": "invalid json: " + err.Error()})
		return err
	}
	if err := normalize(in); err != nil {
		c.JSON(400, gin.H{"error": err.Error()})
		return err
	}
	return nil
}

func (s *state) statusFor(id int) StatusView {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.status[id]
}

func (s *state) sendWol(c *gin.Context) {
	id, err := strconv.Atoi(c.Param("id"))
	if err != nil {
		c.JSON(400, gin.H{"error": "bad id"})
		return
	}
	s.mu.RLock()
	sv := s.find(id)
	var mac, ip string
	if sv != nil {
		mac, ip = sv.MAC, sv.IP
	}
	s.mu.RUnlock()

	if sv == nil {
		c.JSON(404, gin.H{"ok": false, "message": "server not found"})
		return
	}
	if mac == "" {
		c.JSON(400, gin.H{"ok": false, "message": "no mac address configured for this server"})
		return
	}

	msg, err := sendWoL(mac, ip)
	now := time.Now().UTC().Format(time.RFC3339)
	s.mu.Lock()
	s.lastWol[id] = now
	s.mu.Unlock()
	s.save()

	if err != nil {
		c.JSON(200, gin.H{"ok": false, "message": err.Error(), "lastWolAt": now})
		return
	}
	c.JSON(200, gin.H{"ok": true, "message": msg, "lastWolAt": now})
}

// shutdown runs a clean `systemctl poweroff` on the server over SSH (the
// sendWol shape: look up, validate configured, run, record, respond).
// lastShutdownAt is best-effort: it is written after sshPoweroff returns,
// in-memory only (cleared on restart, like lastWolAt), and in the
// self-shutdown case the host dies mid-run so the record is often never
// written at all.
// Returns 200 with ok:false on run failures (unreachable, auth, command
// failure) so the app can report the message; 400/404 for config/lookup
// errors, like sendWol.
func (s *state) shutdown(c *gin.Context) {
	id, err := strconv.Atoi(c.Param("id"))
	if err != nil {
		c.JSON(400, gin.H{"error": "bad id"})
		return
	}
	s.mu.RLock()
	sv := s.find(id)
	var user, keyPath, password, ip string
	configured := false
	if sv != nil {
		user, keyPath, password, ip = sv.SshUser, sv.SshKeyPath, sv.SshPassword, sv.IP
		configured = sv.sshConfigured()
	}
	s.mu.RUnlock()

	if sv == nil {
		c.JSON(404, gin.H{"ok": false, "message": "server not found"})
		return
	}
	if !configured {
		c.JSON(400, gin.H{"ok": false, "message": "no ssh shutdown configured for this server"})
		return
	}

	now := time.Now().UTC().Format(time.RFC3339)
	msg, err := sshPoweroff(shutdownTarget(ip), user, keyPath, password)
	s.mu.Lock()
	s.lastShutdown[id] = now
	s.mu.Unlock()
	s.save()

	if err != nil {
		c.JSON(200, gin.H{"ok": false, "message": err.Error(), "lastShutdownAt": now})
		return
	}
	c.JSON(200, gin.H{"ok": true, "message": msg, "lastShutdownAt": now})
}

// ---------- main ----------

func main() {
	loadDotEnv(".env")

	addr := os.Getenv("ADDR")
	if addr == "" {
		addr = ":8080"
	}
	interval := 10 * time.Second
	if v := os.Getenv("CHECK_INTERVAL"); v != "" {
		if d, err := time.ParseDuration(v); err == nil {
			interval = d
		}
	}

	st := &state{}
	st.auth = loadAuthConfig()
	st.sessions = newSessions()
	st.load()
	go st.checkLoop(interval)

	gin.SetMode(gin.ReleaseMode)
	r := gin.New()
	r.Use(gin.Logger(), gin.Recovery(), corsMiddleware())

	api := r.Group("/api")
	{
		// Public endpoints (health is used by scripts; login issues tokens).
		api.GET("/health", st.health)
		api.POST("/login", st.login)
	}

	// Everything else requires a valid token from /api/login.
	auth := r.Group("/api")
	auth.Use(st.authMiddleware())
	{
		auth.GET("/servers", st.listServers)
		auth.POST("/servers", st.addServer)
		auth.POST("/servers/reorder", st.reorderServers)
		auth.PUT("/servers/:id", st.updateServer)
		auth.DELETE("/servers/:id", st.deleteServer)
		auth.GET("/status", st.listStatus)
		auth.POST("/servers/:id/wol", st.sendWol)
		auth.POST("/servers/:id/shutdown", st.shutdown)
		auth.GET("/version", st.version)
	}

	log.Printf("polex-backend listening on %s (check interval %s, auth on)", addr, interval)
	_ = r.Run(addr)
}

func corsMiddleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		c.Header("Access-Control-Allow-Origin", "*")
		c.Header("Access-Control-Allow-Methods", "GET,POST,PUT,DELETE,OPTIONS")
		c.Header("Access-Control-Allow-Headers", "Content-Type, Authorization")
		if c.Request.Method == "OPTIONS" {
			c.AbortWithStatus(204)
			return
		}
		c.Next()
	}
}
