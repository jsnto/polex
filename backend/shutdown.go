package main

import (
	"errors"
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
// A server with sshUser set plus at least one credential (sshKeyPath and/or
// sshPassword) can be powered off with a clean `systemctl poweroff` over
// SSH. Credentials are tried in order, key first: when sshKeyPath is set
// the key is offered, and if sshd rejects it (an authentication failure)
// the password is offered next within the same handshake. When sshKeyPath
// is empty, the password is used directly. A key file that fails to load
// (missing/unreadable/invalid) is a failed key too: with a password set,
// the password is used on its own and the key error stays visible in the
// result text. The private key lives as a file
// on the backend host (mounted into the container in the Docker deployment);
// the per-server config holds only a path reference to it, never key
// material, and the password sits in the same per-server config blob (this
// is bearer-token-protected home-lab config — no extra secret store).
//
// This is an in-band action: it works only while the target's OS (and
// sshd) are responsive. If the server is wedged or unreachable, the
// command cannot run — that limit is reported honestly by sshPoweroff, and
// an out-of-band mechanism (BMC, smart plug) is the fallback for that
// case.
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
// to offer the action (a user plus at least one credential — like an empty
// MAC disabling WOL). Single source of truth for the predicate.
func (sv Server) sshConfigured() bool {
	return sv.SshUser != "" && (sv.SshKeyPath != "" || sv.SshPassword != "")
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

// sshAuthMethods builds the ordered credential list from the per-server
// config: key first (when a key path is set), then the password (when set).
// x/crypto/ssh offers the methods in this order within one handshake, so a
// key rejected by sshd automatically falls through to the password without
// a second connection — and a key that is ACCEPTED is never followed by a
// password attempt.
//
// It returns the credential list plus a key note: when the key file fails
// to load (missing/unreadable/invalid) and a password is set, the password
// is used on its own and the key error is returned as the note, so the
// caller keeps it visible in the result text; with no password a failed
// key file is a hard local config error. Non-auth failures later on
// (timeout, unreachable, OS not responsive) never burn the password
// fallback.
func sshAuthMethods(keyPath, password string) ([]ssh.AuthMethod, string, error) {
	var auths []ssh.AuthMethod
	var keyNote string
	if keyPath != "" {
		key, err := os.ReadFile(expandHome(keyPath))
		if err != nil {
			keyNote = fmt.Sprintf("cannot read ssh key at %s: %v", keyPath, err)
		} else {
			signer, err := ssh.ParsePrivateKey(key)
			if err != nil {
				keyNote = fmt.Sprintf("invalid ssh key at %s: %v", keyPath, err)
			} else {
				auths = append(auths, ssh.PublicKeys(signer))
			}
		}
	}
	if password != "" {
		auths = append(auths, ssh.Password(password))
	}
	if keyNote != "" && password == "" {
		return nil, "", errors.New(keyNote)
	}
	return auths, keyNote, nil
}

// sshPoweroff connects to addr as user and runs `systemctl poweroff` using
// the credentials in keyPath/password order (see sshAuthMethods: key first,
// password when the key is rejected by sshd or fails to load, password
// directly when no key is configured). It returns a human-facing success
// message, or an honest, distinct error:
//
//   - the key file is missing/unreadable/invalid on the backend host and no
//     password is set — with a password set, the password is used instead
//     and the key error stays visible in the result text;
//   - sshd rejected every configured credential (wrong user, or wrong
//     key and wrong password) — reported only after all of them were
//     tried;
//   - the server is unreachable or its OS is not responsive (the known
//     wedge-case limit of in-band shutdown); a key attempt that fails for
//     such a non-auth reason never burns the password fallback;
//   - the remote command failed (non-zero exit, e.g. no systemd).
//
// A connection that dies right after sshd accepted the command is treated
// as success: the host is powering off and tears down the SSH session, so
// the exit status may never arrive.
func sshPoweroff(addr, user, keyPath, password string) (string, error) {
	auths, keyNote, err := sshAuthMethods(keyPath, password)
	if err != nil {
		return "", err
	}
	if len(auths) == 0 {
		// The handler gates on sshConfigured, so this is unreachable via
		// the API; guard the direct-call path anyway.
		return "", fmt.Errorf("no ssh credentials configured for %s", user)
	}
	msg, err := sshPoweroffWithAuths(addr, user, auths)
	if keyNote == "" {
		return msg, err
	}
	if err != nil {
		return "", fmt.Errorf("%v; key error: %s", err, keyNote)
	}
	return fmt.Sprintf("%s; key error: %s (password used)", msg, keyNote), nil
}

// sshPoweroffWithAuths runs `systemctl poweroff` on addr with the given
// ordered credentials.
func sshPoweroffWithAuths(addr, user string, auths []ssh.AuthMethod) (string, error) {
	// One total deadline for the WHOLE sequence — every credential
	// attempt included. x/crypto/ssh's dial timeout only bounds the TCP
	// connect, so the handshake is bounded by a deadline on the conn
	// itself, and the session start and exec wait race the same deadline.
	deadline := time.Now().Add(sshTotalTimeout)

	cfg := &ssh.ClientConfig{
		User:            user,
		Auth:            auths,
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
		// error text is the discriminator (client_auth.go). This is only
		// reached when sshd actively REJECTED every offered credential —
		// a timeout or unreachable host produces a different error below,
		// which is a non-auth failure and never burns the password
		// fallback.
		if strings.Contains(err.Error(), "unable to authenticate") {
			return "", fmt.Errorf("ssh authentication failed for %s@%s (wrong user or credentials?)", user, addr)
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
