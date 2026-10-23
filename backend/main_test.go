package main

import (
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"reflect"
	"strings"
	"testing"
	"time"

	"github.com/gin-gonic/gin"
)

// ---------- helpers ----------

// chdirTemp moves the test's working directory to a fresh temp dir so the
// handlers' and load/save's data.json never touches the source tree (the
// same isolation newTestBackend in shutdown_test.go applies).
func chdirTemp(t *testing.T) {
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
}

// seededServers is the first-run seed load() installs (the two example
// servers with their IDs assigned).
func seededServers() []Server {
	srvs := newDefaultServers()
	for i := range srvs {
		srvs[i].ID = i + 1
	}
	return srvs
}

// crudRouter builds a state from the given servers and wires the CRUD,
// status, and WOL routes the same way main() does (minus the auth
// middleware, which auth_test.go covers on its own).
func crudRouter(t *testing.T, servers ...Server) (*gin.Engine, *state) {
	t.Helper()
	chdirTemp(t)
	st := &state{
		servers:      servers,
		status:       map[int]StatusView{},
		lastWol:      map[int]string{},
		lastShutdown: map[int]string{},
		nextID:       len(servers) + 1,
	}
	gin.SetMode(gin.TestMode)
	r := gin.New()
	r.GET("/api/servers", st.listServers)
	r.POST("/api/servers", st.addServer)
	r.POST("/api/servers/reorder", st.reorderServers)
	r.PUT("/api/servers/:id", st.updateServer)
	r.DELETE("/api/servers/:id", st.deleteServer)
	r.GET("/api/status", st.listStatus)
	r.POST("/api/servers/:id/wol", st.sendWol)
	return r, st
}

// apiRequest runs one request against the engine and returns the status
// code and the body.
func apiRequest(r http.Handler, method, target, body string) (int, string) {
	req := httptest.NewRequest(method, target, strings.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)
	return w.Code, w.Body.String()
}

// liveLoopbackPort returns a loopback TCP port with a live listener (a
// health check against it connects instantly) and the closer to call.
func liveLoopbackPort(t *testing.T) (int, func()) {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	return ln.Addr().(*net.TCPAddr).Port, func() { ln.Close() }
}

// freeLoopbackPort returns a loopback TCP port that is closed: a health
// check against it gets an immediate refusal (deterministic "offline"
// without the 3 s timeout a dead 192.168.x address would take).
func freeLoopbackPort(t *testing.T) int {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	port := ln.Addr().(*net.TCPAddr).Port
	ln.Close()
	return port
}

// configFile mirrors the data.json on-disk shape.
type configFile struct {
	Servers []Server `json:"servers"`
	NextID  int      `json:"nextId"`
}

func configFileContents(t *testing.T) configFile {
	t.Helper()
	b, err := os.ReadFile(dataFile)
	if err != nil {
		t.Fatalf("read %s: %v", dataFile, err)
	}
	var d configFile
	if err := json.Unmarshal(b, &d); err != nil {
		t.Fatalf("unmarshal %s: %v (%s)", dataFile, err, b)
	}
	return d
}

// ---------- persistence: load / save ----------

// TestLoadSeedsDefaultsAndPersists: a first run (no data.json) seeds the
// two example servers, assigns their IDs, sets nextID past them, and
// writes the seed to disk; a second state loading the file reproduces it.
func TestLoadSeedsDefaultsAndPersists(t *testing.T) {
	chdirTemp(t)
	st := &state{}
	st.load()

	if len(st.servers) != 2 {
		t.Fatalf("seeded servers = %d, want 2", len(st.servers))
	}
	def := newDefaultServers()
	for i, want := range def {
		if st.servers[i].ID != i+1 {
			t.Fatalf("server %d: ID = %d, want %d", i, st.servers[i].ID, i+1)
		}
		if st.servers[i].Name != want.Name || st.servers[i].IP != want.IP ||
			st.servers[i].MAC != want.MAC || st.servers[i].Port != want.Port ||
			st.servers[i].Check != want.Check {
			t.Fatalf("server %d = %+v, want %+v", i, st.servers[i], want)
		}
	}
	if st.nextID != 3 {
		t.Fatalf("nextID = %d, want 3", st.nextID)
	}

	// The seed is written to data.json on first run.
	d := configFileContents(t)
	if len(d.Servers) != 2 || d.NextID != 3 {
		t.Fatalf("data.json = %+v, want the 2 seeded servers and nextId 3", d)
	}

	// A second state loaded from the file reproduces the same config.
	st2 := &state{}
	st2.load()
	if !reflect.DeepEqual(st2.servers, st.servers) || st2.nextID != st.nextID {
		t.Fatalf("reload: servers %+v nextID %d, want the same state", st2.servers, st2.nextID)
	}
}

// TestLoadReadsExistingFile: a hand-written data.json is read back, and the
// file's nextId drives the next ID (not len(servers)).
func TestLoadReadsExistingFile(t *testing.T) {
	chdirTemp(t)
	srvs := []Server{
		{ID: 1, Name: "NAS", IP: "192.168.1.10", MAC: "AA:BB:CC:DD:EE:01", Port: 22, Check: "tcp"},
		{ID: 2, Name: "WS", IP: "192.168.1.11", Port: 22, Check: "tcp"},
		{ID: 3, Name: "LB", IP: "192.168.1.12", Port: 80, Check: "ping"},
	}
	b, err := json.Marshal(configFile{Servers: srvs, NextID: 7})
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(dataFile, b, 0644); err != nil {
		t.Fatal(err)
	}

	st := &state{}
	st.load()
	if !reflect.DeepEqual(st.servers, srvs) {
		t.Fatalf("loaded servers = %+v, want the file's three", st.servers)
	}
	if st.nextID != 7 {
		t.Fatalf("nextID = %d, want 7 (the file's nextId)", st.nextID)
	}
}

// TestLoadToleratesACorruptFile: a non-JSON data.json is logged and skipped
// (empty server list, usable maps); a subsequent save replaces it with
// valid JSON instead of failing.
func TestLoadToleratesACorruptFile(t *testing.T) {
	chdirTemp(t)
	if err := os.WriteFile(dataFile, []byte("not json"), 0644); err != nil {
		t.Fatal(err)
	}

	st := &state{}
	st.load() // must not panic

	if len(st.servers) != 0 {
		t.Fatalf("servers after corrupt load = %+v, want empty", st.servers)
	}
	if st.status == nil || st.lastWol == nil || st.lastShutdown == nil {
		t.Fatalf("status maps not initialized after corrupt load")
	}

	// A subsequent save replaces the corrupt file with valid JSON.
	st.save()
	if _, err := os.Stat(dataFile + ".tmp"); !os.IsNotExist(err) {
		t.Errorf("leftover %s after save: %v", dataFile+".tmp", err)
	}
	b, err := os.ReadFile(dataFile)
	if err != nil {
		t.Fatalf("re-read: %v", err)
	}
	var probe map[string]any
	if err := json.Unmarshal(b, &probe); err != nil {
		t.Fatalf("data.json after save is not valid JSON: %v (%s)", err, b)
	}
}

// TestSaveIsAtomicAndRoundTrips: save() writes via a temp file that is
// renamed into place (no leftover .tmp), and load(save(x)) == x; a second
// save overwrites cleanly.
func TestSaveIsAtomicAndRoundTrips(t *testing.T) {
	chdirTemp(t)
	srvs := seededServers()
	st := &state{
		servers:      srvs,
		status:       map[int]StatusView{},
		lastWol:      map[int]string{},
		lastShutdown: map[int]string{},
		nextID:       3,
	}
	st.save()

	if _, err := os.Stat(dataFile + ".tmp"); !os.IsNotExist(err) {
		t.Fatalf("leftover %s after save: %v", dataFile+".tmp", err)
	}
	d := configFileContents(t)
	if !reflect.DeepEqual(d.Servers, srvs) || d.NextID != 3 {
		t.Fatalf("data.json = %+v, want the saved servers and nextId 3", d)
	}

	// Round-trip: load reproduces the saved state.
	st2 := &state{}
	st2.load()
	if !reflect.DeepEqual(st2.servers, srvs) || st2.nextID != 3 {
		t.Fatalf("round-trip: %+v nextID %d, want the saved state", st2.servers, st2.nextID)
	}

	// A second save overwrites (not appends) and round-trips again.
	st2.servers = append(st2.servers, Server{ID: 3, Name: "New", IP: "192.168.1.12", Port: 22, Check: "tcp"})
	st2.nextID = 4
	st2.save()
	st3 := &state{}
	st3.load()
	if len(st3.servers) != 3 || st3.nextID != 4 {
		t.Fatalf("after re-save: %d servers nextID %d, want 3 and 4", len(st3.servers), st3.nextID)
	}
}

// ---------- CRUD handlers ----------

// TestAddServerHandler: POST /api/servers validates/normalizes the body,
// assigns nextID, checks the server, responds with its StatusView, and
// persists the addition.
func TestAddServerHandler(t *testing.T) {
	r, _ := crudRouter(t, seededServers()...)

	// A live loopback port so the immediate health check reports online.
	port, closeLive := liveLoopbackPort(t)
	defer closeLive()
	code, body := apiRequest(r, http.MethodPost, "/api/servers",
		fmt.Sprintf(`{"name":"  fileserver ","ip":"127.0.0.1","mac":"aa:bb:cc:dd:ee:09","port":%d}`, port))
	if code != http.StatusOK {
		t.Fatalf("status = %d (body %s)", code, body)
	}
	var view StatusView
	if err := json.Unmarshal([]byte(body), &view); err != nil {
		t.Fatalf("body: %v (%s)", err, body)
	}
	if view.ID != 3 {
		t.Errorf("id = %d, want 3 (after the two seeded servers)", view.ID)
	}
	if view.Name != "fileserver" {
		t.Errorf("name = %q, want the trimmed \"fileserver\"", view.Name)
	}
	if view.MAC != "AA:BB:CC:DD:EE:09" {
		t.Errorf("mac = %q, want the uppercased MAC", view.MAC)
	}
	if view.Check != "tcp" {
		t.Errorf("check = %q, want the defaulted \"tcp\"", view.Check)
	}
	if !view.Online {
		t.Errorf("online = false, want true (the loopback port is live)")
	}
	if view.LastChecked == "" || view.LastChecked == "never" {
		t.Errorf("lastChecked = %q, want a timestamp (the add runs a check)", view.LastChecked)
	}

	// The server list grew and the new ID was persisted.
	code, body = apiRequest(r, http.MethodGet, "/api/servers", "")
	if code != http.StatusOK {
		t.Fatalf("list status = %d (body %s)", code, body)
	}
	var list []Server
	if err := json.Unmarshal([]byte(body), &list); err != nil {
		t.Fatalf("list body: %v (%s)", err, body)
	}
	if len(list) != 3 || list[2].ID != 3 || list[2].Name != "fileserver" {
		t.Fatalf("list = %+v, want 3 servers ending in id 3 \"fileserver\"", list)
	}
	d := configFileContents(t)
	if len(d.Servers) != 3 || d.NextID != 4 {
		t.Fatalf("data.json = %+v, want 3 servers and nextId 4", d)
	}
}

// TestAddServerRejectsInvalidBodies: the handler-level 400s — the same
// normalize() rules shutdown_test.go pins at the unit level — leave the
// server list and the persisted nextID untouched.
func TestAddServerRejectsInvalidBodies(t *testing.T) {
	r, st := crudRouter(t, seededServers()...)
	cases := []string{
		`{"ip":"127.0.0.1"}`,                                     // no name
		`{"name":"x"}`,                                            // no ip
		`{"name":"x","ip":"not-an-ip"}`,                           // bad ip
		`{"name":"x","ip":"127.0.0.1","mac":"zz:00"}`,             // bad mac
		`{"name":"x","ip":"127.0.0.1","check":"carrier-pigeon"}`,  // bad check
		`{"name":"x","ip":"127.0.0.1","sshUser":"root"}`,          // ssh user without credentials
		`{bad json`,
	}
	for _, body := range cases {
		code, resp := apiRequest(r, http.MethodPost, "/api/servers", body)
		if code != http.StatusBadRequest {
			t.Errorf("add %s: status = %d, want 400 (body %s)", body, code, resp)
		}
	}

	// Nothing was created.
	code, body := apiRequest(r, http.MethodGet, "/api/servers", "")
	if code != http.StatusOK {
		t.Fatalf("list status = %d (body %s)", code, body)
	}
	var list []Server
	if err := json.Unmarshal([]byte(body), &list); err != nil {
		t.Fatalf("list body: %v (%s)", err, body)
	}
	if len(list) != 2 {
		t.Fatalf("list after rejected adds = %d servers, want 2", len(list))
	}
	// Rejected adds never reach save() (no data.json write) and leave the
	// in-memory ID counter untouched.
	if st.nextID != 3 {
		t.Fatalf("nextID = %d, want 3 (unchanged by the rejected adds)", st.nextID)
	}
}

// TestUpdateServerHandler: PUT /api/servers/:id re-validates, replaces the
// entry in place (a body-supplied id cannot re-key it), checks the updated
// server, and persists.
func TestUpdateServerHandler(t *testing.T) {
	r, _ := crudRouter(t, seededServers()...)
	port, closeLive := liveLoopbackPort(t)
	defer closeLive()

	code, body := apiRequest(r, http.MethodPut, "/api/servers/1",
		fmt.Sprintf(`{"name":"nas-renamed","ip":"127.0.0.1","mac":"aa:bb:cc:dd:ee:01","port":%d}`, port))
	if code != http.StatusOK {
		t.Fatalf("status = %d (body %s)", code, body)
	}
	var view StatusView
	if err := json.Unmarshal([]byte(body), &view); err != nil {
		t.Fatalf("body: %v (%s)", err, body)
	}
	if view.ID != 1 || view.Name != "nas-renamed" || view.MAC != "AA:BB:CC:DD:EE:01" {
		t.Errorf("view = %+v, want id 1 renamed with the uppercased MAC", view)
	}
	if !view.Online {
		t.Errorf("online = false, want true (the updated loopback port is live)")
	}

	// A body-supplied id does not re-key the server: the PUT to /1 stays /1.
	code, body = apiRequest(r, http.MethodPut, "/api/servers/1",
		fmt.Sprintf(`{"id":99,"name":"still-one","ip":"127.0.0.1","mac":"AA:BB:CC:DD:EE:01","port":%d}`, port))
	if code != http.StatusOK {
		t.Fatalf("status = %d (body %s)", code, body)
	}
	if err := json.Unmarshal([]byte(body), &view); err != nil {
		t.Fatalf("body: %v (%s)", err, body)
	}
	if view.ID != 1 || view.Name != "still-one" {
		t.Errorf("view = %+v, want id 1 (the body's id 99 must be ignored)", view)
	}
	code, body = apiRequest(r, http.MethodGet, "/api/servers", "")
	var list []Server
	if err := json.Unmarshal([]byte(body), &list); err != nil {
		t.Fatalf("list body: %v (%s)", err, body)
	}
	if len(list) != 2 || list[0].ID != 1 || list[1].ID != 2 {
		t.Fatalf("list = %+v, want ids [1 2] (no re-keyed 99)", list)
	}

	// Persisted.
	d := configFileContents(t)
	if d.Servers[0].Name != "still-one" || d.NextID != 3 {
		t.Fatalf("data.json = %+v, want the updated server 1 and nextId 3", d)
	}

	// Errors: unknown server, malformed id, invalid body.
	if code, _ := apiRequest(r, http.MethodPut, "/api/servers/999", `{"name":"x","ip":"127.0.0.1"}`); code != http.StatusNotFound {
		t.Errorf("update unknown id: status = %d, want 404", code)
	}
	if code, _ := apiRequest(r, http.MethodPut, "/api/servers/abc", `{"name":"x","ip":"127.0.0.1"}`); code != http.StatusBadRequest {
		t.Errorf("update bad id: status = %d, want 400", code)
	}
	if code, _ := apiRequest(r, http.MethodPut, "/api/servers/1", `{"ip":"127.0.0.1"}`); code != http.StatusBadRequest {
		t.Errorf("update invalid body: status = %d, want 400", code)
	}
}

// TestDeleteServerHandler: DELETE /api/servers/:id removes the entry from
// the config AND the status/WOL records, persists the removal, and does not
// reuse the freed ID.
func TestDeleteServerHandler(t *testing.T) {
	r, st := crudRouter(t, seededServers()...)
	// Give server 2 a status entry and a WOL record so the delete must be
	// seen cleaning both up.
	st.setStatus(seededServers()[1], true, 1, "2026-01-01T00:00:00Z")
	st.mu.Lock()
	st.lastWol[2] = "2026-01-01T00:00:00Z"
	st.mu.Unlock()

	code, body := apiRequest(r, http.MethodDelete, "/api/servers/2", "")
	if code != http.StatusOK {
		t.Fatalf("status = %d (body %s)", code, body)
	}
	if !strings.Contains(body, `"ok":true`) {
		t.Errorf("body = %s, want ok:true", body)
	}

	// The list shrank...
	code, body = apiRequest(r, http.MethodGet, "/api/servers", "")
	var list []Server
	if err := json.Unmarshal([]byte(body), &list); err != nil {
		t.Fatalf("list body: %v (%s)", err, body)
	}
	if len(list) != 1 || list[0].ID != 1 {
		t.Fatalf("list = %+v, want just server 1", list)
	}
	// ...and its status entry (with the WOL record) is gone.
	code, body = apiRequest(r, http.MethodGet, "/api/status", "")
	var items []StatusView
	if err := json.Unmarshal([]byte(body), &items); err != nil {
		t.Fatalf("status body: %v (%s)", err, body)
	}
	if len(items) != 1 || items[0].ID != 1 {
		t.Fatalf("status = %+v, want just server 1", items)
	}
	d := configFileContents(t)
	if len(d.Servers) != 1 || d.NextID != 3 {
		t.Fatalf("data.json = %+v, want just server 1 and nextId 3", d)
	}

	// The freed ID is not reused: the next add continues from nextID.
	port, closeLive := liveLoopbackPort(t)
	defer closeLive()
	code, body = apiRequest(r, http.MethodPost, "/api/servers",
		fmt.Sprintf(`{"name":"third","ip":"127.0.0.1","port":%d}`, port))
	if code != http.StatusOK {
		t.Fatalf("re-add status = %d (body %s)", code, body)
	}
	var added StatusView
	if err := json.Unmarshal([]byte(body), &added); err != nil {
		t.Fatalf("body: %v (%s)", err, body)
	}
	if added.ID != 3 {
		t.Errorf("re-added id = %d, want 3 (the deleted id 2 must not be reused)", added.ID)
	}

	// Unknown and malformed ids.
	if code, _ := apiRequest(r, http.MethodDelete, "/api/servers/999", ""); code != http.StatusNotFound {
		t.Errorf("delete unknown id: status = %d, want 404", code)
	}
	if code, _ := apiRequest(r, http.MethodDelete, "/api/servers/abc", ""); code != http.StatusBadRequest {
		t.Errorf("delete bad id: status = %d, want 400", code)
	}

	// Deleting everything leaves an empty (not null) status array.
	if code, _ := apiRequest(r, http.MethodDelete, "/api/servers/1", ""); code != http.StatusOK {
		t.Errorf("delete 1: status = %d, want 200", code)
	}
	if code, _ := apiRequest(r, http.MethodDelete, "/api/servers/3", ""); code != http.StatusOK {
		t.Errorf("delete 3: status = %d, want 200", code)
	}
	code, body = apiRequest(r, http.MethodGet, "/api/status", "")
	if code != http.StatusOK || body != "[]" {
		t.Errorf("status with no servers = %d %s, want 200 []", code, body)
	}
}

// TestReorderServersHandler: POST /api/servers/reorder applies the given
// order, keeps unmentioned servers at the end in their current relative
// order (a partial or bogus list can't drop servers), and persists.
func TestReorderServersHandler(t *testing.T) {
	r, _ := crudRouter(t, seededServers()...) // current order: [1,2]

	// Full order [2,1].
	code, body := apiRequest(r, http.MethodPost, "/api/servers/reorder", `{"order":[2,1]}`)
	if code != http.StatusOK || !strings.Contains(body, `"ok":true`) {
		t.Fatalf("reorder status = %d (body %s)", code, body)
	}
	var ids func() []int
	ids = func() []int {
		_, body := apiRequest(r, http.MethodGet, "/api/servers", "")
		var list []Server
		if err := json.Unmarshal([]byte(body), &list); err != nil {
			t.Fatalf("list body: %v (%s)", err, body)
		}
		out := make([]int, 0, len(list))
		for _, s := range list {
			out = append(out, s.ID)
		}
		return out
	}
	if got := ids(); !reflect.DeepEqual(got, []int{2, 1}) {
		t.Fatalf("order = %v, want [2 1]", got)
	}

	// Partial order [1]: 1 moves first, the unmentioned 2 keeps its
	// relative position at the end.
	if code, _ := apiRequest(r, http.MethodPost, "/api/servers/reorder", `{"order":[1]}`); code != http.StatusOK {
		t.Fatalf("partial reorder status = %d, want 200", code)
	}
	if got := ids(); !reflect.DeepEqual(got, []int{1, 2}) {
		t.Fatalf("order = %v, want [1 2] (unmentioned server kept)", got)
	}

	// Unknown ids in the order are ignored, servers are never dropped.
	if code, _ := apiRequest(r, http.MethodPost, "/api/servers/reorder", `{"order":[99,1]}`); code != http.StatusOK {
		t.Fatalf("reorder with unknown id: status = %d, want 200", code)
	}
	if got := ids(); !reflect.DeepEqual(got, []int{1, 2}) {
		t.Fatalf("order = %v, want [1 2] (unknown id ignored)", got)
	}

	// An empty order is a no-op (everything stays where it is).
	if code, _ := apiRequest(r, http.MethodPost, "/api/servers/reorder", `{"order":[]}`); code != http.StatusOK {
		t.Fatalf("empty reorder status = %d, want 200", code)
	}
	if got := ids(); !reflect.DeepEqual(got, []int{1, 2}) {
		t.Fatalf("order = %v, want [1 2] (empty order keeps the current order)", got)
	}

	// Invalid JSON.
	if code, _ := apiRequest(r, http.MethodPost, "/api/servers/reorder", `nope`); code != http.StatusBadRequest {
		t.Errorf("invalid reorder body: status = %d, want 400", code)
	}

	// Persisted: the file's order matches the live list.
	d := configFileContents(t)
	got := make([]int, 0, len(d.Servers))
	for _, s := range d.Servers {
		got = append(got, s.ID)
	}
	if !reflect.DeepEqual(got, []int{1, 2}) {
		t.Fatalf("data.json order = %v, want [1 2]", got)
	}
}

// TestListStatusHandler: GET /api/status reports one view per server in
// server order — "never" before the first check, the check result after,
// and recorded WOL timestamps carried across checks.
func TestListStatusHandler(t *testing.T) {
	r, st := crudRouter(t, seededServers()...)

	// Before any check: one view per server, "never", offline, and empty
	// WOL/shutdown timestamps.
	code, body := apiRequest(r, http.MethodGet, "/api/status", "")
	if code != http.StatusOK {
		t.Fatalf("status = %d (body %s)", code, body)
	}
	var items []StatusView
	if err := json.Unmarshal([]byte(body), &items); err != nil {
		t.Fatalf("body: %v (%s)", err, body)
	}
	if len(items) != 2 || items[0].ID != 1 || items[1].ID != 2 {
		t.Fatalf("items = %+v, want servers [1 2] in server order", items)
	}
	for i, v := range items {
		if v.LastChecked != "never" {
			t.Errorf("item %d: lastChecked = %q, want \"never\" before any check", i, v.LastChecked)
		}
		if v.Online {
			t.Errorf("item %d: online = true before any check", i)
		}
		if v.LastWolAt != "" || v.LastShutdownAt != "" {
			t.Errorf("item %d: lastWolAt/lastShutdownAt = %q/%q, want empty", i, v.LastWolAt, v.LastShutdownAt)
		}
	}

	// Point the checks at loopback targets (one live port, one closed one)
	// so the check cycle is instant and deterministic.
	live, closeLive := liveLoopbackPort(t)
	defer closeLive()
	dead := freeLoopbackPort(t)
	st.mu.Lock()
	st.servers[0].IP = "127.0.0.1"
	st.servers[0].Port = live
	st.servers[1].IP = "127.0.0.1"
	st.servers[1].Port = dead
	st.mu.Unlock()
	st.checkAll()

	code, body = apiRequest(r, http.MethodGet, "/api/status", "")
	if code != http.StatusOK {
		t.Fatalf("status = %d (body %s)", code, body)
	}
	if err := json.Unmarshal([]byte(body), &items); err != nil {
		t.Fatalf("body: %v (%s)", err, body)
	}
	if !items[0].Online {
		t.Errorf("server 1 (live loopback port): online = false, want true")
	}
	if items[0].LastChecked == "" || items[0].LastChecked == "never" {
		t.Errorf("server 1: lastChecked = %q, want a timestamp after the check", items[0].LastChecked)
	}
	if items[1].Online {
		t.Errorf("server 2 (closed loopback port): online = true, want false")
	}

	// A recorded WOL timestamp survives a check (setStatus carries the
	// lastWol record into the view).
	st.mu.Lock()
	st.lastWol[1] = "2026-01-02T00:00:00Z"
	st.mu.Unlock()
	st.checkAll()
	code, body = apiRequest(r, http.MethodGet, "/api/status", "")
	if code != http.StatusOK {
		t.Fatalf("status = %d (body %s)", code, body)
	}
	if err := json.Unmarshal([]byte(body), &items); err != nil {
		t.Fatalf("body: %v (%s)", err, body)
	}
	if items[0].LastWolAt != "2026-01-02T00:00:00Z" {
		t.Errorf("server 1: lastWolAt = %q, want the recorded timestamp kept across checks", items[0].LastWolAt)
	}
}

// ---------- WOL handler ----------

// TestSendWolHandler: POST /api/servers/:id/wol sends the magic packet
// (verified on the wire by the loopback listener), records lastWolAt in
// the response and the status, and 400s/404s on config and lookup errors.
func TestSendWolHandler(t *testing.T) {
	// The UDP listener receives what the handler sends.
	udpLn, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.ParseIP("127.0.0.1")})
	if err != nil {
		t.Fatalf("listen udp: %v", err)
	}
	defer udpLn.Close()
	udpLn.SetReadDeadline(time.Now().Add(5 * time.Second))
	target := fmt.Sprintf("127.0.0.1:%d", udpLn.LocalAddr().(*net.UDPAddr).Port)
	t.Setenv("WOL_BROADCAST", target)

	r, st := crudRouter(t, seededServers()...)
	// Point the checks at loopback so the checkAll below is instant.
	live, closeLive := liveLoopbackPort(t)
	defer closeLive()
	dead := freeLoopbackPort(t)
	st.mu.Lock()
	st.servers[0].IP = "127.0.0.1"
	st.servers[0].Port = live
	st.servers[1].IP = "127.0.0.1"
	st.servers[1].Port = dead
	st.mu.Unlock()

	// A server without a MAC gets the 400 (ok:false) config error.
	code, body := apiRequest(r, http.MethodPost, "/api/servers",
		fmt.Sprintf(`{"name":"nomac","ip":"127.0.0.1","port":%d}`, freeLoopbackPort(t)))
	if code != http.StatusOK {
		t.Fatalf("add status = %d (body %s)", code, body)
	}
	var added StatusView
	if err := json.Unmarshal([]byte(body), &added); err != nil {
		t.Fatalf("body: %v (%s)", err, body)
	}
	code, body = apiRequest(r, http.MethodPost, fmt.Sprintf("/api/servers/%d/wol", added.ID), "")
	if code != http.StatusBadRequest {
		t.Fatalf("wol with no mac: status = %d (body %s), want 400", code, body)
	}
	var noMac struct {
		OK      bool   `json:"ok"`
		Message string `json:"message"`
	}
	if err := json.Unmarshal([]byte(body), &noMac); err != nil {
		t.Fatalf("body: %v (%s)", err, body)
	}
	if noMac.OK || !strings.Contains(noMac.Message, "no mac address") {
		t.Errorf("no-mac response = ok:%v %q, want ok:false with the no-mac message", noMac.OK, noMac.Message)
	}

	// Wake the seeded NAS (server 1, MAC AA:BB:CC:DD:EE:01).
	code, body = apiRequest(r, http.MethodPost, "/api/servers/1/wol", "")
	if code != http.StatusOK {
		t.Fatalf("wol status = %d (body %s)", code, body)
	}
	var resp struct {
		OK        bool   `json:"ok"`
		Message   string `json:"message"`
		LastWolAt string `json:"lastWolAt"`
	}
	if err := json.Unmarshal([]byte(body), &resp); err != nil {
		t.Fatalf("body: %v (%s)", err, body)
	}
	if !resp.OK {
		t.Fatalf("ok = false (body %s)", body)
	}
	if !strings.Contains(resp.Message, target) {
		t.Errorf("message = %q, want it to mention the sent target %s", resp.Message, target)
	}
	if resp.LastWolAt == "" {
		t.Errorf("lastWolAt empty in the response")
	}

	// The handler records lastWol in the map and persists it; the status
	// views pick it up on the next check cycle (setStatus carries the map
	// entry into the view), so the timestamp surfaces in /api/status after
	// a checkAll.
	st.checkAll()
	code, body = apiRequest(r, http.MethodGet, "/api/status", "")
	if code != http.StatusOK {
		t.Fatalf("status = %d (body %s)", code, body)
	}
	var items []StatusView
	if err := json.Unmarshal([]byte(body), &items); err != nil {
		t.Fatalf("body: %v (%s)", err, body)
	}
	if items[0].LastWolAt != resp.LastWolAt {
		t.Errorf("status lastWolAt = %q, want the response's %q", items[0].LastWolAt, resp.LastWolAt)
	}

	// Unknown and malformed ids.
	code, body = apiRequest(r, http.MethodPost, "/api/servers/999/wol", "")
	if code != http.StatusNotFound {
		t.Errorf("wol unknown id: status = %d, want 404 (body %s)", code, body)
	}
	if !strings.Contains(body, `"ok":false`) {
		t.Errorf("wol unknown id body = %s, want ok:false", body)
	}
	if code, _ := apiRequest(r, http.MethodPost, "/api/servers/abc/wol", ""); code != http.StatusBadRequest {
		t.Errorf("wol bad id: status = %d, want 400", code)
	}
}
