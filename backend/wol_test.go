package main

import (
	"bytes"
	"encoding/hex"
	"fmt"
	"net"
	"strings"
	"testing"
	"time"
)

// The WOL magic packet for wolPinnedMAC, pinned byte-for-byte: 6 x 0xFF
// followed by the MAC repeated 16 times (102 bytes total). A packet that is
// off by a single byte never wakes the NIC, so the exact wire layout is
// pinned here as data rather than re-derived from the buildWoLPacket
// algorithm inside the assertion.
const (
	wolPinnedMAC = "AA:BB:CC:DD:EE:FF"

	wolPinnedHex = "ffffffffffff" +
		"aabbccddeeffaabbccddeeffaabbccddeeffaabbccddeeff" +
		"aabbccddeeffaabbccddeeffaabbccddeeffaabbccddeeff" +
		"aabbccddeeffaabbccddeeffaabbccddeeffaabbccddeeff" +
		"aabbccddeeffaabbccddeeffaabbccddeeffaabbccddeeff"
)

func TestValidMac(t *testing.T) {
	cases := []struct {
		mac  string
		want bool
	}{
		{"AA:BB:CC:DD:EE:FF", true},
		{"aa:bb:cc:dd:ee:ff", true}, // case-insensitive
		{"AA-BB-CC-DD-EE-FF", true}, // hyphen form (net.ParseMAC)
		{"00:11:22:33:44:55", true},
		{"", false},
		{"AA:BB:CC:DD:EE", false},       // five groups
		{"AA:BB:CC:DD:EE:FF:00", false}, // seven groups
		{"GG:HH:II:JJ:KK:LL", false},    // non-hex groups
		{"AA:BB:CC:DD:EE:GG", false},    // last group non-hex
		{"AA BB CC DD EE FF", false},    // space-separated
		{"aabbccddeeff", false},         // no separators
	}
	for _, c := range cases {
		if got := validMac(c.mac); got != c.want {
			t.Errorf("validMac(%q) = %v, want %v", c.mac, got, c.want)
		}
	}
}

// TestBuildWoLPacketPinsTheMagicBytes pins the full 102-byte packet: the
// six 0xFF header bytes plus the MAC repeated sixteen times.
func TestBuildWoLPacketPinsTheMagicBytes(t *testing.T) {
	pkt, err := buildWoLPacket(wolPinnedMAC)
	if err != nil {
		t.Fatalf("buildWoLPacket(%q): %v", wolPinnedMAC, err)
	}
	if len(pkt) != 102 {
		t.Fatalf("packet length = %d, want 102", len(pkt))
	}
	if got := hex.EncodeToString(pkt); got != wolPinnedHex {
		t.Fatalf("packet bytes differ from the pinned magic packet:\n got %s\nwant %s", got, wolPinnedHex)
	}

	// A lowercase input produces the identical bytes (ParseMAC normalizes
	// the case), so the packet depends only on the six MAC octets.
	pktLower, err := buildWoLPacket(strings.ToLower(wolPinnedMAC))
	if err != nil {
		t.Fatalf("buildWoLPacket(lowercase): %v", err)
	}
	if !bytes.Equal(pkt, pktLower) {
		t.Errorf("lowercase input: %x, want the same bytes as the uppercase input", pktLower)
	}
}

func TestBuildWoLPacketRejectsBadMACs(t *testing.T) {
	for _, mac := range []string{"", "nope", "AA:BB:CC:DD:EE", "GG:BB:CC:DD:EE:FF", "AA:BB:CC:DD:EE:FF:00"} {
		if pkt, err := buildWoLPacket(mac); err == nil {
			t.Errorf("buildWoLPacket(%q) succeeded with %d bytes, want an error", mac, len(pkt))
		}
	}
}

func TestSubnetBroadcast24(t *testing.T) {
	cases := []struct {
		ip, want string
	}{
		{"192.168.1.10", "192.168.1.255"},
		{"10.0.0.5", "10.0.0.255"},
		{"172.16.5.99", "172.16.5.255"},
		{"192.168.1.0", "192.168.1.255"},     // the network address maps to the same broadcast
		{"192.168.1.255", "192.168.1.255"},   // idempotent on the broadcast itself
		{"2001:db8::1", ""},                  // IPv6: no /24 broadcast
		{"not-an-ip", ""},                    // unparseable
		{"", ""},
	}
	for _, c := range cases {
		if got := subnetBroadcast24(c.ip); got != c.want {
			t.Errorf("subnetBroadcast24(%q) = %q, want %q", c.ip, got, c.want)
		}
	}
}

// TestSendWoLDeliversThePinnedPacket drives sendWoL end to end against a
// loopback UDP listener (addressed via WOL_BROADCAST) and asserts the
// arriving datagram is exactly the pinned magic packet — from MAC string to
// wire bytes.
func TestSendWoLDeliversThePinnedPacket(t *testing.T) {
	ln, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.ParseIP("127.0.0.1")})
	if err != nil {
		t.Fatalf("listen udp: %v", err)
	}
	defer ln.Close()
	ln.SetReadDeadline(time.Now().Add(5 * time.Second))
	port := ln.LocalAddr().(*net.UDPAddr).Port
	target := fmt.Sprintf("127.0.0.1:%d", port)
	t.Setenv("WOL_BROADCAST", target)

	msg, err := sendWoL(wolPinnedMAC, "192.168.1.10")
	if err != nil {
		t.Fatalf("sendWoL: %v", err)
	}
	if !strings.Contains(msg, target) {
		t.Fatalf("message = %q, want it to mention %s", msg, target)
	}

	buf := make([]byte, 102)
	n, err := ln.Read(buf)
	if err != nil {
		t.Fatalf("read from %s: %v", target, err)
	}
	if n != 102 {
		t.Fatalf("datagram = %d bytes, want 102", n)
	}
	if got := hex.EncodeToString(buf[:n]); got != wolPinnedHex {
		t.Fatalf("delivered bytes differ from the pinned magic packet:\n got %s\nwant %s", got, wolPinnedHex)
	}
}

func TestSendWoLRejectsBadMAC(t *testing.T) {
	t.Setenv("WOL_BROADCAST", "")
	if msg, err := sendWoL("not-a-mac", "192.168.1.10"); err == nil {
		t.Fatalf("sendWoL with a bad MAC succeeded: %q", msg)
	}
}
