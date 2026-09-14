package main

import (
	"fmt"
	"net"
	"os"
	"strings"
)

func validMac(mac string) bool {
	_, err := net.ParseMAC(mac)
	return err == nil
}

// buildWoLPacket builds the 102-byte "magic packet":
// 6 x 0xFF followed by the MAC address repeated 16 times.
func buildWoLPacket(mac string) ([]byte, error) {
	m, err := net.ParseMAC(mac)
	if err != nil {
		return nil, fmt.Errorf("invalid mac address %q: %w", mac, err)
	}
	pkt := make([]byte, 0, 102)
	for i := 0; i < 6; i++ {
		pkt = append(pkt, 0xFF)
	}
	for i := 0; i < 16; i++ {
		pkt = append(pkt, m...)
	}
	return pkt, nil
}

// subnetBroadcast24 returns the /24 subnet broadcast address for ip,
// e.g. 192.168.1.10 -> 192.168.1.255. Returns "" if ip is not IPv4.
func subnetBroadcast24(ip string) string {
	p := net.ParseIP(ip)
	if p == nil {
		return ""
	}
	v4 := p.To4()
	if v4 == nil {
		return ""
	}
	return fmt.Sprintf("%d.%d.%d.255", v4[0], v4[1], v4[2])
}

// sendWoL sends the magic packet via UDP to the broadcast address(es).
// Targets: 255.255.255.255:9, the /24 subnet broadcast of serverIP:9,
// and any extra addresses listed in the WOL_BROADCAST env var
// (comma separated, optional :port).
func sendWoL(mac, serverIP string) (string, error) {
	pkt, err := buildWoLPacket(mac)
	if err != nil {
		return "", err
	}

	targets := []string{"255.255.255.255:9"}
	if bc := subnetBroadcast24(serverIP); bc != "" {
		targets = append(targets, bc+":9")
	}
	if extra := os.Getenv("WOL_BROADCAST"); extra != "" {
		for _, t := range strings.Split(extra, ",") {
			t = strings.TrimSpace(t)
			if t == "" {
				continue
			}
			if !strings.Contains(t, ":") {
				t += ":9"
			}
			targets = append(targets, t)
		}
	}

	var sent []string
	var firstErr error
	for _, target := range targets {
		conn, err := net.Dial("udp", target)
		if err != nil {
			if firstErr == nil {
				firstErr = fmt.Errorf("dial %s: %w", target, err)
			}
			continue
		}
		if _, err := conn.Write(pkt); err != nil {
			_ = conn.Close()
			if firstErr == nil {
				firstErr = fmt.Errorf("send to %s: %w", target, err)
			}
			continue
		}
		_ = conn.Close()
		sent = append(sent, target)
	}
	if len(sent) == 0 {
		return "", fmt.Errorf("failed to send wake-on-lan packet: %v", firstErr)
	}
	return fmt.Sprintf("WOL packet sent to %s", strings.Join(sent, ", ")), nil
}
