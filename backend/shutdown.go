package main

import (
	"fmt"
	"net"
	"os"
	"strconv"
	"strings"
	"time"

	"golang.org/x/crypto/ssh"
)

// ---------- SSH shutdown ----------
//
// A server with both sshUser and sshKeyPath set can be powered off with a
// clean `systemctl poweroff` over SSH (key auth only — no password auth).
// The private key lives as a file on the backend host (mounted into the
// container in the Docker deployment); the per-server config holds only a
// path reference to it, never key material. This is an in-band action: it
// works only while the target's OS (and sshd) are responsive. If the
// server is wedged or unreachable, the command cannot run — that limit is
// reported honestly by sshPoweroff, and an out-of-band mechanism (BMC,
// smart plug) is the fallback for that case.
//
// Host-key checking is intentionally off (ssh.InsecureIgnoreHostKey): the
// backend sits on a trusted LAN behind bearer-token auth, which matches
// the plain-HTTP posture of the rest of polex. Pin the host key (e.g.
// ssh.FixedHostKey) if you need MITM protection on a less trusted segment.

const shutdownCommand = "systemctl poweroff"

// sshTotalTimeout is the one deadline for the whole shutdown SSH sequence
// (dial, handshake, session start, exec wait): it stays under the app's
// 5 s OkHttp read timeout so the backend always answers first. A var so
// tests can shrink it.
var sshTotalTimeout = 4500 * time.Millisecond

// sshPort is the port shutdown connections use (sshd by convention). A
// var so tests can point it at an in-process SSH server.
var sshPort = 22

// sshConfigured reports whether the server has enough SSH shutdown config
// to offer the action (both fields set — like an empty MAC disabling WOL).
func (sv Server) sshConfigured() bool {
	return sv.SshUser != "" && sv.SshKeyPath != ""
}

// expandHome turns a leading "~" into $HOME for key paths; other paths are
// returned unchanged.
func expandHome(path string) string {
	if path == "~" || strings.HasPrefix(path, "~/") {
		if home, err := os.UserHomeDir(); err == nil {
			return home + strings.TrimPrefix(path, "~")
		}
	}
	return path
}

// shutdownTarget is the SSH address (ip:port) for a server's shutdown
// connection.
func shutdownTarget(ip string) string {
	return net.JoinHostPort(ip, strconv.Itoa(sshPort))
}

// sshPoweroff connects to addr (user + private key from keyPath) and runs
// `systemctl poweroff`. It returns a human-facing success message, or an
// honest, distinct error:
//
//   - the key file is missing/unreadable/invalid on the backend host;
//   - sshd rejected the key (wrong user or key);
//   - the server is unreachable or its OS is not responsive (the known
//     wedge-case limit of in-band shutdown);
//   - the remote command failed (non-zero exit, e.g. no systemd).
//
// A connection that dies right after sshd accepted the command is treated
// as success: the host is powering off and tears down the SSH session, so
// the exit status may never arrive.
func sshPoweroff(addr, user, keyPath string) (string, error) {
	key, err := os.ReadFile(expandHome(keyPath))
	if err != nil {
		return "", fmt.Errorf("cannot read ssh key at %s: %v", keyPath, err)
	}
	signer, err := ssh.ParsePrivateKey(key)
	if err != nil {
		return "", fmt.Errorf("invalid ssh key at %s: %v", keyPath, err)
	}

	// One total deadline for the whole sequence. x/crypto/ssh's dial
	// timeout only bounds the TCP connect, so the handshake is bounded by
	// a deadline on the conn itself, and the session start and exec wait
	// race the same deadline.
	deadline := time.Now().Add(sshTotalTimeout)

	cfg := &ssh.ClientConfig{
		User:            user,
		Auth:            []ssh.AuthMethod{ssh.PublicKeys(signer)},
		HostKeyCallback: ssh.InsecureIgnoreHostKey(), // trusted LAN; see above
	}
	conn, err := net.DialTimeout("tcp", addr, time.Until(deadline))
	if err != nil {
		return "", fmt.Errorf("server unreachable or OS not responsive: %v", err)
	}
	_ = conn.SetDeadline(deadline)
	cc, chans, reqs, err := ssh.NewClientConn(conn, addr, cfg)
	_ = conn.SetDeadline(time.Time{})
	if err != nil {
		conn.Close()
		// x/crypto/ssh has no exported auth-failure type; its handshake
		// error text is the discriminator (client_auth.go).
		if strings.Contains(err.Error(), "unable to authenticate") {
			return "", fmt.Errorf("ssh authentication failed for %s@%s (wrong user or key?)", user, addr)
		}
		return "", fmt.Errorf("server unreachable or OS not responsive: %v", err)
	}
	client := ssh.NewClient(cc, chans, reqs)
	defer client.Close()

	// A half-wedged host can finish the handshake and then never answer
	// the channel-open or exec request, so the session start races the
	// same deadline.
	var stderr strings.Builder // remote stderr, for honest failure messages
	type sessionStart struct {
		sess *ssh.Session
		err  error
	}
	started := make(chan sessionStart, 1)
	go func() {
		sess, err := client.NewSession()
		if err != nil {
			started <- sessionStart{err: fmt.Errorf("ssh session: %v", err)}
			return
		}
		sess.Stderr = &stderr
		if err := sess.Start(shutdownCommand); err != nil {
			sess.Close()
			started <- sessionStart{err: fmt.Errorf("server refused the poweroff command: %v", err)}
			return
		}
		started <- sessionStart{sess: sess}
	}()
	var sess *ssh.Session
	select {
	case res := <-started:
		if res.err != nil {
			return "", res.err
		}
		sess = res.sess
	case <-time.After(time.Until(deadline)):
		client.Close()
		return "", fmt.Errorf("server unreachable or OS not responsive: session start timed out")
	}
	defer sess.Close()

	// Wait briefly for the exit status. Outcomes:
	//   nil            — systemctl exited 0: the poweroff job is enqueued.
	//   *ssh.ExitError — the command failed; report its stderr.
	//   anything else  — the connection died while the host powered off:
	//                    the command was accepted, treat as success.
	// If no outcome arrives in time, the command was still accepted by
	// sshd; the host is just slow to answer.
	done := make(chan error, 1)
	go func() { done <- sess.Wait() }()
	select {
	case werr := <-done:
		switch e := werr.(type) {
		case nil:
			return "shutdown command accepted", nil
		case *ssh.ExitError:
			detail := strings.TrimSpace(stderr.String())
			if detail == "" {
				detail = e.Error() // e.g. "exit status 127"
			}
			return "", fmt.Errorf("poweroff command failed: %s", detail)
		default:
			return "shutdown command accepted (connection closed as the server powered off)", nil
		}
	case <-time.After(time.Until(deadline)):
		return "shutdown command accepted; server did not confirm (timed out)", nil
	}
}
